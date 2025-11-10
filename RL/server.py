# RL/server.py
from fastapi import FastAPI
from pydantic import BaseModel
from typing import List, Optional
import numpy as np

app = FastAPI()

class PredictReq(BaseModel):
    sd: dict
    demand: int
    path: List[int]
    path_width_candidate: int
    per_edge_free_links: List[int]
    src_remaining_qubits: int
    dst_remaining_qubits: int
    priority: Optional[int] = 0
    wait_time: Optional[int] = 0
    global_load: Optional[float] = 0.0
    recent_success_rate: Optional[float] = 0.0

class PredictResp(BaseModel):
    w: int

def featurize(req: PredictReq) -> np.ndarray:
    return np.array([
        req.demand,
        req.path_width_candidate,
        len(req.path),
        min(req.per_edge_free_links) if req.per_edge_free_links else 0,
        np.mean(req.per_edge_free_links) if req.per_edge_free_links else 0,
        req.src_remaining_qubits,
        req.dst_remaining_qubits,
        req.priority or 0,
        req.wait_time or 0,
        req.global_load or 0.0,
        req.recent_success_rate or 0.0
    ], dtype=np.float32)

def policy_infer(x: np.ndarray) -> int:
    # 先用规则：w = min(demand, width_cand, min_edge_free)
    demand = int(x[0]); width_cand = int(x[1]); min_edge = int(x[3])
    return max(0, min(demand, width_cand, min_edge))

@app.post("/predict", response_model=PredictResp)
def predict(req: PredictReq):
    x = featurize(req)
    w = policy_infer(x)
    return PredictResp(w=w)
