# ppo_training.py
# -*- coding: utf-8 -*-
"""
训练脚本（主循环）：
- 参数配置
- 实例化 PPOAgent
- for episode_i:
    - state = env.reset()
    - episode_reward 累计
    - for step_i:
        - action, value, logprob = agent.get_action(state)
        - next_state, reward, done, info = env.step(action)
            * next_state: 动作执行后的下一时刻观测（obs/状态向量）
            * reward:     本步即时奖励（float）
            * done:       终止标记（bool）；例如到达时隙上限、所有请求完成或场景结束
            * info:       额外诊断信息（dict），可包含：
                           - "succ_pairs": 本步成功交付数
                           - "waste":      本步浪费数
                           - "util":       预算利用率
                           - ... 任何你想记录的指标
        - 累计 episode_reward += reward
        - 写入记忆 agent.replay_buffer.add_memo(state, action, logprob, value, reward, done)
        - state = next_state
        - 判断是否该触发一次 agent.update()（例如回合结束、或累计步数达到 update_every）
    - 打印/日志/保存
    - REWARD_BUFFER 记录最近 K 个回合的累计奖励做移动平均
"""

import os
from collections import deque
from typing import Optional

import numpy as np
import torch
import matplotlib.pyplot as plt

from ppo_agent import PPOAgent
from toy_env import ToyAllocEnv

# ========== 你的环境 ==========
# 假设你已经在工程中有一个符合 OpenAI Gym-like 接口的环境：
#   - reset() -> state(np.ndarray)
#   - step(action: np.ndarray) -> (next_state(np.ndarray), reward(float), done(bool), info(dict))
# 这里放一个占位导入（你用自己的替换掉即可）
# from your_project.envs import YourEnv
YourEnv = None  # 请替换成你自己的环境类

# ========== 全局超参 ==========
SEED               = 42
DEVICE             = "cuda" if torch.cuda.is_available() else "cpu"

MAX_EPISODES       = 500
MAX_STEPS_PER_EP   = 256          # 每个 episode 最多步数（例如时隙数上限）
UPDATE_EVERY       = 256          # 收集多少步触发一次 PPO 更新（一般与 MAX_STEPS_PER_EP 一致也可）
PRINT_FREQ         = 10           # 打印频率（按 episode）
SAVE_FREQ          = 100          # 保存频率（按 episode）
REWARD_BUFFER_SIZE = 100          # 最近回合奖励的滑动窗口

# PPO 超参（传给 Agent）
GAMMA        = 0.99
LAMBDA_GAE   = 0.95
CLIP_RATIO   = 0.2
PI_LR        = 3e-4
VF_LR        = 1e-3
ENT_COEF     = 0.0
VF_COEF      = 0.5
UPDATE_EPOCHS= 10
MINIBATCH    = 64
HIDDEN_SIZE  = 128
INIT_LOG_STD = 0.0
MAX_GRAD_NORM= 0.5

# ========== 随机数固定 ==========
def set_seed(seed: int):
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)

# ========== 主训练 ==========
def main(
    env: Optional[object] = None,
    save_dir: str = "./ckpt",
    export_policy_path: Optional[str] = None
):
    os.makedirs(save_dir, exist_ok=True)
    set_seed(SEED)

    # 1) 准备环境
    env = ToyAllocEnv(n_requests=8, cap_max=5, horizon=64, seed=42, unfair_lambda=0.2, ar_rho=0.9)
    if env is None:
        if YourEnv is None:
            raise RuntimeError("请把 `YourEnv` 替换为你的环境类或传入 `env` 实例！")
        # env = YourEnv(seed=SEED)
        # 刚加

    # 2) 读取初始观测 & 维度
    state = env.reset()
    assert isinstance(state, np.ndarray), "环境的 reset() 应返回 np.ndarray 的状态"
    obs_dim = state.size

    # 你应该从环境或配置里知道 action 维度（例如 n_max）
    # 这里假设环境暴露了 action_dim 属性，否则你就手填
    act_dim = getattr(env, "action_dim", None)
    if act_dim is None:
        raise RuntimeError("请从环境获得 action 维度，例如 env.action_dim 或手动指定。")

    # 3) 实例化 Agent
    agent = PPOAgent(
        obs_dim=obs_dim, act_dim=act_dim, hidden_size=HIDDEN_SIZE, device=DEVICE,
        gamma=GAMMA, lam=LAMBDA_GAE, clip_ratio=CLIP_RATIO, pi_lr=PI_LR, vf_lr=VF_LR,
        ent_coef=ENT_COEF, vf_coef=VF_COEF, update_epochs=UPDATE_EPOCHS,
        minibatch_size=MINIBATCH, init_log_std=INIT_LOG_STD, max_grad_norm=MAX_GRAD_NORM
    )

    reward_buffer = deque(maxlen=REWARD_BUFFER_SIZE)
    global_step = 0
    # 刚加
    ep_rewards = []  # 每回合总回报
    pi_losses_curve = []  # 每次 update 的平均策略损失
    vf_losses_curve = []  # 每次 update 的平均价值损失

    for episode_i in range(1, MAX_EPISODES + 1):
        state = env.reset()
        episode_reward = 0.0
        steps_this_ep = 0

        # 4) 单回合小循环
        while True:
            # 4.1 策略给出动作和价值
            action, value, logprob = agent.get_action(state)
            # 4.2 与环境交互（请保证 env.step 能接受你的 action 形式）
            next_state, reward, done, info = env.step(action)

            # 刚加
            if steps_this_ep in (0, 1):  # 只打印回合前两步，避免刷屏
                print(f"[Ep {episode_i:03d} Step {steps_this_ep:02d}] "
                      f"caps={info['caps']} action~[0,1]={np.round(action, 3)} "
                      f"alloc={info['alloc']} util={info['util']:.3f} unfair={info['unfair']:.3f} reward={reward:.3f}")
            # 4.3 写入记忆
            agent.replay_buffer.add_memo(
                state=torch.as_tensor(state, dtype=torch.float32),
                action=torch.as_tensor(action, dtype=torch.float32),
                logprob=logprob,            # 已是 tensor
                value=torch.as_tensor(value, dtype=torch.float32),
                reward=reward,
                done=done,
            )

            # 4.4 累计奖励/步数 & 状态更新
            episode_reward += float(reward)
            steps_this_ep  += 1
            global_step    += 1
            state = next_state

            # 4.5 触发更新
            if done or (steps_this_ep % UPDATE_EVERY == 0) or (steps_this_ep >= MAX_STEPS_PER_EP):
                try:
                    # agent.update()刚加
                    metrics = agent.update()
                finally:
                    agent.replay_buffer.clear_memo()
            #     刚加
                pi_losses_curve.append(metrics["pi_loss"])
                vf_losses_curve.append(metrics["vf_loss"])

            # 4.6 回合结束判定
            if done or (steps_this_ep >= MAX_STEPS_PER_EP):
                break

        # 5) 统计与保存
        ep_rewards.append(episode_reward)
        reward_buffer.append(episode_reward)
        avg_reward = np.mean(reward_buffer)

        if (episode_i % PRINT_FREQ) == 0:
            print(f"[Episode {episode_i:04d}] "
                  f"reward={episode_reward:.3f}  avg@{len(reward_buffer)}={avg_reward:.3f}  "
                  f"steps={steps_this_ep}  global_step={global_step}")

        if (episode_i % SAVE_FREQ) == 0:
            save_path = os.path.join(save_dir, f"ppo_ep{episode_i}.pt")
            torch.save({
                "model": agent.state_dict(),
                "config": {
                    "obs_dim": obs_dim,
                    "act_dim": act_dim,
                    "hidden_size": HIDDEN_SIZE,
                }
            }, save_path)
            print(f"Saved checkpoint to {save_path}")

    # 可选：导出策略参数或别的产物（比如均值向量/脚本化模型）
    if export_policy_path:
        torch.save(agent.state_dict(), export_policy_path)
        print(f"Exported policy to {export_policy_path}")


    # ========== 画图（奖励/损失）==========刚加
    # 1) 回合奖励曲线
    plt.figure()
    plt.plot(ep_rewards, label="episode reward")
    if len(ep_rewards) >= 10:
        # 简单滑窗平均
        k = 10
        smooth = np.convolve(ep_rewards, np.ones(k) / k, mode='valid')
        plt.plot(range(k - 1, k - 1 + len(smooth)), smooth, label="moving avg (k=10)")
    plt.xlabel("Episode")
    plt.ylabel("Reward")
    plt.title("PPO on ToyAllocEnv - Reward")
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(save_dir, "reward_curve.png"))
    print(f"Saved figure: {os.path.join(save_dir, 'reward_curve.png')}")

    # 2) 损失曲线
    plt.figure()
    plt.plot(pi_losses_curve, label="pi_loss")
    plt.plot(vf_losses_curve, label="vf_loss")
    plt.xlabel("Update #")
    plt.ylabel("Loss")
    plt.title("PPO Loss Curves")
    plt.legend()
    plt.tight_layout()
    plt.savefig(os.path.join(save_dir, "loss_curves.png"))
    print(f"Saved figure: {os.path.join(save_dir, 'loss_curves.png')}")

if __name__ == "__main__":
    # 你可以在这里实例化自己的环境再传给 main(env=...)
    # env = YourEnv(...)
    # main(env)
    main()
    # raise SystemExit("请在你的项目里导入 main(env=你的环境) 调用；或替换上面的 YourEnv。")
