# toy_env.py
# -*- coding: utf-8 -*-
import numpy as np

class ToyAllocEnv:
    """
    一个可用于 PPO 快速自检的极简环境：
    - 动作: [0,1]^n  ->  环境内投影为整数分配 floor(a_i * cap_i)
    - 状态: 拼接 [caps/ CAP_MAX, global_sum/ (n*CAP_MAX)] 作为简单特征
    - 奖励: util - LAMBDA * unfair
      util   = sum(alloc)/max(sum(caps),1)
      unfair = std(alloc')，其中 alloc' = alloc / max(1, sum(alloc))
    """

    def __init__(self, n_requests=8, cap_max=5, horizon=64, seed=42, unfair_lambda=0.2, ar_rho: float | None = None):
        self.n = int(n_requests)
        self.action_dim = self.n
        self.CAP_MAX = int(cap_max)
        self.H = int(horizon)
        self.unfair_lambda = float(unfair_lambda)
        self.rng = np.random.default_rng(seed)
        self.t = 0
        self.caps = None
        self.ar_rho = ar_rho

    def _obs(self):
        caps_norm = self.caps / self.CAP_MAX
        gsum = np.array([self.caps.sum() / (self.n * self.CAP_MAX)], dtype=np.float32)
        return np.concatenate([caps_norm.astype(np.float32), gsum], axis=0)

    def reset(self):
        self.t = 0
        self.caps = self.rng.integers(low=0, high=self.CAP_MAX+1, size=self.n, endpoint=False)
        return self._obs()

    def step(self, action):
        # 1) 规范输入，确保 [0,1]^n
        a = np.asarray(action, dtype=np.float32)
        assert a.shape == (self.n,), f"action shape {a.shape} != ({self.n},)"
        a = np.clip(a, 0.0, 1.0)

        # 2) 投影为整数分配
        alloc = np.floor(a * self.caps).astype(np.int32)

        # 3) 计算奖励
        cap_sum = int(self.caps.sum())
        alloc_sum = int(alloc.sum())
        util = (alloc_sum / cap_sum) if cap_sum > 0 else 0.0

        if alloc_sum > 0:
            alloc_ratio = alloc / alloc_sum
            unfair = float(np.std(alloc_ratio))
        else:
            unfair = 0.0

        reward = util - self.unfair_lambda * unfair

        # 4) 下一状态
        self.t += 1
        done = (self.t >= self.H)
        if not done:
            if self.ar_rho is None:
                if self.vary_per_step:
                    self.caps = self.rng.integers(low=0, high=self.CAP_MAX + 1, size=self.n)
            else:
                # AR(1): caps = rho*caps + noise
                noise = self.rng.normal(loc=0.0, scale=1.0, size=self.n)
                cont = self.ar_rho * self.caps + (1 - self.ar_rho) * (self.CAP_MAX / 2) + noise
                cont = np.clip(np.round(cont), 0, self.CAP_MAX)
                self.caps = cont.astype(int)
        next_obs = self._obs()

        # 5) info 里打印检查的上下文
        info = {
            "caps": self.caps.copy() if not done else self.caps,
            "alloc": alloc,
            "util": util,
            "unfair": unfair
        }
        return next_obs, float(reward), bool(done), info
