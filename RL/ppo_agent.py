# ppo_agent.py
# -*- coding: utf-8 -*-
from dataclasses import dataclass
from typing import List, Tuple, Optional
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

# ========== 工具 ==========
LOG2 = np.log(2.0)

def atanh(x: torch.Tensor) -> torch.Tensor:
    x = x.clamp(-1 + 1e-6, 1 - 1e-6)
    return 0.5 * (torch.log1p(x) - torch.log1p(-x))
# =========================
# Replay Buffer
# =========================
@dataclass
class ReplayMemory:
    """简洁版 PPO 记忆模块：按 step 追加，按需整体取样/清空。
    存储字段：
      - states:   [T, obs_dim]            观测
      - actions:  [T, act_dim]            动作（连续向量）
      - logprobs: [T]                     旧策略下的 log π(a|s)
      - values:   [T]                     旧价值 v(s)
      - rewards:  [T]                     单步奖励 r_t
      - dones:    [T]                     终止标记（True/False）
    """

    states:   List[torch.Tensor]
    actions:  List[torch.Tensor]
    logprobs: List[torch.Tensor]
    values:   List[torch.Tensor]
    rewards:  List[torch.Tensor]
    dones:    List[torch.Tensor]

    def add_memo(
        self,
        state: torch.Tensor,
        action: torch.Tensor,
        logprob: torch.Tensor,
        value: torch.Tensor,
        reward: float,
        done: bool
    ):
        """写入一步的轨迹样本"""
        self.states.append(state.detach())
        self.actions.append(action.detach())
        self.logprobs.append(logprob.detach())
        self.values.append(value.detach())
        self.rewards.append(torch.as_tensor(reward, dtype=torch.float32))
        self.dones.append(torch.as_tensor(done, dtype=torch.float32))

    def sample(self) -> Tuple[torch.Tensor, ...]:
        """一次性打包为张量（PPO 通常按全批次/mini-batch 迭代）"""
        states   = torch.stack(self.states,   dim=0)
        actions  = torch.stack(self.actions,  dim=0)
        logprobs = torch.stack(self.logprobs, dim=0)
        values   = torch.stack(self.values,   dim=0)
        rewards  = torch.stack(self.rewards,  dim=0)
        dones    = torch.stack(self.dones,    dim=0)
        return states, actions, logprobs, values, rewards, dones

    def clear_memo(self):
        """清空缓存，开始新一轮收集"""
        self.states.clear()
        self.actions.clear()
        self.logprobs.clear()
        self.values.clear()
        self.rewards.clear()
        self.dones.clear()


# =========================
# PPO Agent (单类整合 Actor+Critic)
# =========================
class PPOAgent(nn.Module):
    """不拆分 AC 的 PPO 实现：内部自带策略/价值网络。
    - forward(s) -> mu, std, v
    - get_action(s) -> action(np), value(float), logprob(tensor)
    - update(memory) -> 进行若干 epoch 的mini-batch PPO优化
    - 属性 replay_buffer: ReplayMemory
    """

    def __init__(
        self,
        obs_dim: int,
        act_dim: int,
        hidden_size: int = 64,
        device: str = "cpu",
        # PPO 超参
        gamma: float = 0.99,
        lam: float = 0.95,
        clip_ratio: float = 0.2,
        pi_lr: float = 3e-4, # actor的学习率
        vf_lr: float = 1e-3, # critic的学习率
        ent_coef: float = 0.0,
        vf_coef: float = 0.5,
        update_epochs: int = 10,
        minibatch_size: int = 64,
        # 其它
        init_log_std: float = -0.5,
        max_grad_norm: Optional[float] = 0.5
    ):
        super().__init__()
        self.device = torch.device(device)
        self.obs_dim = obs_dim
        self.act_dim = act_dim
        # 策略网络（高斯策略，连续动作）
        self.pi_body = nn.Sequential(
            nn.Linear(obs_dim, hidden_size), nn.ReLU(),
            nn.Linear(hidden_size, hidden_size), nn.ReLU(),
        )
        self.mu_head = nn.Linear(hidden_size, act_dim)          # 输出均值 μ
        self.log_std = nn.Parameter(torch.ones(act_dim) * init_log_std)  # 对数标准差（可学习），探索程度

        # 价值网络
        self.vf = nn.Sequential(
            nn.Linear(obs_dim, hidden_size), nn.ReLU(),
            nn.Linear(hidden_size, hidden_size), nn.ReLU(),
            nn.Linear(hidden_size, 1)
        )

        # 优化器
        self.pi_opt = torch.optim.Adam(
            list(self.pi_body.parameters()) + list(self.mu_head.parameters()) + [self.log_std],
            lr=pi_lr
        ) # 策略优化器
        self.vf_opt = torch.optim.Adam(self.vf.parameters(), lr=vf_lr) # 价值优化器

        # PPO/GAE 超参
        self.gamma = gamma
        self.lam = lam
        self.clip_ratio = clip_ratio
        self.ent_coef = ent_coef
        self.vf_coef = vf_coef
        self.update_epochs = update_epochs
        self.minibatch_size = minibatch_size
        self.max_grad_norm = max_grad_norm
        # 记忆
        self.replay_buffer = ReplayMemory(
            states=[], actions=[], logprobs=[], values=[], rewards=[], dones=[]
        ) # {s,a,策略,估计的价值,r,done}
        self.to(self.device)

    # ---------- 前向传播 ----------
    def forward(self, states: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """返回 (mu, std, v)：策略的均值和标准差、以及价值"""
        h = self.pi_body(states) # 状态输入策略网络
        mu = self.mu_head(h)     # 均值（动作期望），注意mu是否做约束tanh / clip
        std = torch.exp(self.log_std).clamp(1e-6, 1e3) # 标准差
        v = self.vf(states).squeeze(-1) # 状态输入价值网络
        return mu, std, v # 返回动作分布参数（均值 mu、标准差 std）和状态价值 v

    # ---------- logprob（带 tanh+0.5 缩放校正） ----------
    def _logprob_squashed(self, mu: torch.Tensor, std: torch.Tensor, action_01: torch.Tensor) -> torch.Tensor:
        """
        给定策略参数(mu,std)与 [0,1] 动作，计算 log π(a)。
        a = (tanh(z)+1)/2,  z ~ N(mu,std)
        log π(a) = log N(z | mu,std) + Σ [ log(0.5) + log(1 - tanh(z)^2) ]
        其中 z = atanh(2a-1)
        不知道干嘛，一会儿看下
        """
        a = action_01.clamp(1e-6, 1 - 1e-6)
        y = 2.0 * a - 1.0  # [-1,1]
        z = atanh(y)  # 反变换
        base = torch.distributions.Normal(mu, std)
        log_base = base.log_prob(z).sum(-1)  # Σ log N(z)
        # tanh 的雅可比： log(1 - tanh(z)^2)
        log_j_tanh = torch.log1p(-y.pow(2) + 1e-12).sum(-1)  # Σ log(1 - y^2)
        # 仿射缩放 0.5 的雅可比： Σ log(0.5) = -act_dim*log(2)
        log_j_aff = -self.act_dim * LOG2
        return log_base + log_j_tanh + log_j_aff

    # ---------- 动作采样 ----------
    @torch.no_grad()
    def get_action(self, state: np.ndarray) -> Tuple[np.ndarray, float, torch.Tensor]:
        """给单个或一批 state，返回 (action(np), value(float), logprob(tensor))。
        - action: 连续动作（可为 w 分配向量），建议在 env 内部进行非负/预算投影；
        - value: 价值标量（若为 batch 则返回 float(v[0])）；
        - logprob: 用于 PPO 更新的策略（不要转 np）。
        """
        s = torch.as_tensor(state, dtype=torch.float32, device=self.device)
        if s.ndim == 1:
            s = s.unsqueeze(0)   # [1, obs_dim]

        mu, std, v = self.forward(s)
        base = torch.distributions.Normal(mu, std)
        z = base.rsample()  # 重参数化
        y = torch.tanh(z)  # [-1,1]
        a = 0.5 * (y + 1.0)  # [0,1]
        logp = self._logprob_squashed(mu, std, a)

        a_np = a.squeeze(0).cpu().numpy() if a.shape[0] == 1 else a.cpu().numpy()
        v_scalar = v.squeeze(0).item() if v.shape[0] == 1 else v[0].item()
        return a_np, v_scalar, logp.squeeze(0) if logp.shape[0] == 1 else logp

    # ---------- GAE优势函数估计 ----------
    @staticmethod
    def _compute_gae(rewards, values, dones, gamma=0.99, lam=0.95):
        """rewards/values/dones 均为 [T] 张量；values 需包含 bootstrap 的 v_{T}（即 len= T+1）"""
        T = rewards.shape[0]
        adv = torch.zeros(T, dtype=torch.float32, device=rewards.device)
        gae = 0.0
        for t in reversed(range(T)):
            mask = 1.0 - float(dones[t])
            delta = rewards[t] + gamma * values[t + 1] * mask - values[t]
            gae = delta + gamma * lam * mask * gae
            adv[t] = gae
        ret = adv + values[:-1]
        return adv, ret

    # ---------- 更新 ----------
    def update(self):
        """对当前 replay_buffer 做一次 PPO 更新（多 epoch + mini-batch）"""
        states, actions, logp_old, values, rewards, dones = self.replay_buffer.sample()
        states   = states.to(self.device)
        actions  = actions.to(self.device)
        logp_old = logp_old.to(self.device)
        values   = values.to(self.device)
        rewards  = rewards.to(self.device)
        dones    = dones.to(self.device)

        # 计算 bootstrap 值 v_T
        with torch.no_grad():
            _, _, v_last = self.forward(states[-1:])
        values_with_boot = torch.cat([values, v_last], dim=0)  # [T+1]

        # GAE / Return
        adv, ret = self._compute_gae(rewards, values_with_boot, dones, self.gamma, self.lam)
        adv = (adv - adv.mean()) / (adv.std() + 1e-8)

        # 打乱索引
        N = states.shape[0]
        idx = torch.randperm(N, device=self.device)
        # 刚加
        pi_losses = []
        vf_losses = []
        entropies = []
        for _ in range(self.update_epochs):
            for start in range(0, N, self.minibatch_size):
                j = idx[start:start + self.minibatch_size]
                s_b = states[j]
                a_b = actions[j]
                lp_old_b = logp_old[j]
                adv_b = adv[j]
                ret_b = ret[j]

                mu, std, vpred = self.forward(s_b)
                lp = self._logprob_squashed(mu, std, a_b)

                # PPO Clip 损失
                ratio = torch.exp(lp - lp_old_b)
                surr1 = ratio * adv_b
                surr2 = torch.clamp(ratio, 1.0 - self.clip_ratio, 1.0 + self.clip_ratio) * adv_b
                # 熵：对 squashed 分布精确熵较复杂，这里使用 base 熵近似或直接 0
                base = torch.distributions.Normal(mu, std)
                approx_entropy = base.entropy().sum(-1)

                pi_loss = -torch.min(surr1, surr2).mean() - self.ent_coef * approx_entropy.mean()
                vf_loss = F.mse_loss(vpred, ret_b)

                # 反向与裁剪
                self.pi_opt.zero_grad(set_to_none=True)
                pi_loss.backward()
                if self.max_grad_norm is not None:
                    nn.utils.clip_grad_norm_(list(self.pi_body.parameters()) + list(self.mu_head.parameters()) + [self.log_std], self.max_grad_norm)
                self.pi_opt.step()

                self.vf_opt.zero_grad(set_to_none=True)
                (self.vf_coef * vf_loss).backward()
                if self.max_grad_norm is not None:
                    nn.utils.clip_grad_norm_(self.vf.parameters(), self.max_grad_norm)
                self.vf_opt.step()
                # 刚加的
                # 收集 batch 级指标
                pi_losses.append(pi_loss.detach().item())
                vf_losses.append(vf_loss.detach().item())
                entropies.append(approx_entropy.detach().mean().item())

                # 返回 epoch 平均损失，方便打印/画图
            return {
                "pi_loss": float(np.mean(pi_losses)) if pi_losses else 0.0,
                "vf_loss": float(np.mean(vf_losses)) if vf_losses else 0.0,
                "entropy": float(np.mean(entropies)) if entropies else 0.0,
            }
