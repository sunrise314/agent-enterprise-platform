#!/usr/bin/env python3
"""
评估批量运行器（占位骨架）。

对应 docs/03_模块设计/03_04_evaluation.md（面试核心）
功能：
  - 读取测试集
  - 调用 biz-platform 评估接口逐条执行
  - 聚合指标输出报告

用法：
  python run_eval.py --run-name v1.0-regression --filter badcase
"""
import argparse
import json
import sys
import urllib.request

API_BASE = "http://localhost:8080/api"


def trigger_eval(name: str, testcase_filter: dict) -> dict:
    """触发评估批次。"""
    payload = json.dumps({"name": name, "testcaseFilter": testcase_filter}).encode("utf-8")
    req = urllib.request.Request(
        f"{API_BASE}/eval/run",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_report(run_id: int) -> dict:
    """拉取评估结果。"""
    with urllib.request.urlopen(f"{API_BASE}/eval/run/{run_id}") as resp:
        return json.loads(resp.read().decode("utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-name", required=True)
    parser.add_argument("--filter", default="all", help="positive/boundary/adversarial/badcase/all")
    args = parser.parse_args()

    result = trigger_eval(args.run_name, {"type": args.filter})
    run_id = result["data"]["runId"]
    print(f"[Eval] 批次已触发: run_id={run_id}")

    report = get_report(run_id)
    m = report["data"]["metrics"]
    print("\n===== 评估汇总 =====")
    print(f"总用例: {report['data']['total']}  成功: {report['data']['success']}  失败: {report['data']['fail']}")
    print(f"任务完成率:   {m['taskCompletionRate']:.2%}")
    print(f"工具调用准确率: {m['toolCallAccuracy']:.2%}")
    print(f"幻觉率:       {m['hallucinationRate']:.2%}")
    print(f"P95 延迟:     {m['p95LatencyMs']}ms")
    print(f"平均 token:   {m['avgTokenTotal']}")


if __name__ == "__main__":
    sys.exit(main())
