#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 / 清理演示录音数据（用于预览与 UI 验证，勿在生产库执行）。

原理：转写文本由本脚本提供，DFA 命中位置调用后端真实接口 /api/dictionary/test 计算，
保证与线上匹配器完全一致，因此需要后端处于运行状态。

用法：
    python3 tools/seed_demo_data.py              # 写入 12 条演示录音（id 从 1000 起）
    python3 tools/seed_demo_data.py --clean      # 仅删除演示数据
    python3 tools/seed_demo_data.py --api http://localhost:8082 --container record-audit-mysql
"""
import argparse
import json
import subprocess
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

SQL_FILE = Path("/tmp/seed_demo_data.sql")
MYSQL_USER, MYSQL_PASSWORD, MYSQL_DB = "audit", "audit123", "record_audit"
ID_BASE = 1000  # 演示数据 id 起始，删除时按 id >= ID_BASE 清理


def hits_for(api: str, text: str):
    req = urllib.request.Request(
        f"{api}/api/dictionary/test",
        data=json.dumps({"text": text}).encode(),
        headers={"Content-Type": "application/json"},
    )
    return json.loads(urllib.request.urlopen(req, timeout=10).read())


def esc(s):
    if s is None:
        return "NULL"
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"


def build_records(now):
    def ts(days_ago, hour, minute):
        d = (now - timedelta(days=days_ago)).replace(hour=hour, minute=minute, second=0, microsecond=0)
        return d.strftime("%Y-%m-%d %H:%M:%S")

    return [
        dict(id=1001, name="2026-09-20_09-14_inbound_8821.mp3", size=1_842_311, dur=214, status="NEEDS_REVIEW",
             up=ts(0, 9, 14),
             text="Thank you for holding. I already explained the refund policy twice. If you keep pushing me like this, "
                  "you stupid bitch, I will kill you, do you understand? Do not call this number again, I am warning you.",
             ai=dict(violation=True, violationType="INSULT", violationTypeLabel="辱骂",
                     reason="Direct insult ('stupid bitch') and an explicit personal threat ('I will kill you') aimed at the caller.",
                     confidence=0.96,
                     targetSentence="If you keep pushing me like this, you stupid bitch, I will kill you, do you understand?")),
        dict(id=1002, name="2026-09-20_10-02_inbound_8842.mp3", size=2_204_998, dur=268, status="NEEDS_REVIEW",
             up=ts(0, 10, 2),
             text="Listen, this is not a scam at all. If you buy the gift card right now I guarantee you will double your money "
                  "by Friday, it is completely risk free. Just give me the card number and your password and I will handle the rest.",
             ai=dict(violation=True, violationType="FRAUD", violationTypeLabel="诈骗诱导",
                     reason="Induces the customer to buy gift cards with a guaranteed doubling of money and requests card number and password.",
                     confidence=0.94,
                     targetSentence="If you buy the gift card right now I guarantee you will double your money by Friday, it is completely risk free.")),
        dict(id=1003, name="2026-09-19_14-33_outbound_7731.mp3", size=1_120_450, dur=142, status="VIOLATION_CONFIRMED",
             up=ts(1, 14, 33),
             text="To be honest with you, the previous agent who handled this ticket was a fucking idiot. I have never seen "
                  "such a stupid team, no wonder you people keep losing customers.",
             ai=dict(violation=True, violationType="INSULT", violationTypeLabel="辱骂",
                     reason="Uses 'fucking idiot' and calls the customer 'stupid' in a derogatory manner.",
                     confidence=0.91,
                     targetSentence="The previous agent who handled this ticket was a fucking idiot."),
             review=dict(result="CONFIRMED_VIOLATION", type="INSULT", label="辱骂", reviewer="张质检",
                         comment="确认辱骂同事与客户，按红线处理并通知主管。", at=ts(1, 16, 5))),
        dict(id=1004, name="2026-09-19_16-10_inbound_7756.mp3", size=986_233, dur=128, status="FALSE_POSITIVE",
             up=ts(1, 16, 10),
             text="I understand this is frustrating, damn, but the system is temporarily down and I cannot process it today. "
                  "Sir, I would never scam you, this is a standard verification step and you can confirm it on our website.",
             ai=dict(violation=True, violationType="FRAUD", violationTypeLabel="诈骗诱导",
                     reason="Keyword 'scam' matched, but the sentence is a denial used to reassure the customer.",
                     confidence=0.42,
                     targetSentence="Sir, I would never scam you, this is a standard verification step."),
             review=dict(result="FALSE_POSITIVE", type=None, label=None, reviewer="李合规",
                         comment="“scam” 出现在否认语境，属关键词误报，已作为合规反例入库。", at=ts(1, 16, 40))),
        dict(id=1005, name="2026-09-18_11-05_inbound_7690.mp3", size=1_540_772, dur=198, status="VIOLATION_CONFIRMED",
             up=ts(2, 11, 5),
             text="I do not want to talk to that faggot supervisor anymore, you people are all the same. "
                  "Honestly, you should all go back to your own country and stop taking our jobs.",
             ai=dict(violation=True, violationType="DISCRIMINATION", violationTypeLabel="歧视",
                     reason="Homophobic slur plus xenophobic remarks about nationality.",
                     confidence=0.97,
                     targetSentence="I do not want to talk to that faggot supervisor anymore, you people are all the same."),
             review=dict(result="CONFIRMED_VIOLATION", type="DISCRIMINATION", label="歧视", reviewer="张质检",
                         comment="歧视性言论属实，通知 HR 并纳入专项培训。", at=ts(2, 13, 12))),
        dict(id=1006, name="2026-09-18_15-47_outbound_7702.mp3", size=1_331_002, dur=176, status="COMPLIANT",
             up=ts(2, 15, 47),
             text="For security I will send a one time code to your phone. Please never share your password with anyone, "
                  "including our staff. I can confirm the plan and the billing date once you read me the last four digits.",
             ai=dict(violation=False, violationType="NONE", violationTypeLabel="无",
                     reason="Mentions password and card digits only to warn the customer about security, no privacy leak.",
                     confidence=0.12, targetSentence="")),
        dict(id=1007, name="2026-09-17_09-30_inbound_7601.mp3", size=842_119, dur=104, status="COMPLIANT",
             up=ts(3, 9, 30),
             text="Good morning, thanks for calling support. I have opened a case for the delayed shipment and the "
                  "replacement will be dispatched within two business days. You will receive an email confirmation shortly."),
        dict(id=1008, name="2026-09-16_13-22_inbound_7512.mp3", size=1_098_664, dur=151, status="FALSE_POSITIVE",
             up=ts(4, 13, 22),
             text="The customer told me his previous agent said shut up during the call, and he asked me to check the "
                  "recording. I apologised on behalf of the team and escalated it to the quality desk.",
             ai=dict(violation=True, violationType="HARASSMENT", violationTypeLabel="骚扰",
                     reason="'shut up' appears inside a reported quotation, not as an actual utterance by the agent.",
                     confidence=0.28,
                     targetSentence="His previous agent said shut up during the call."),
             review=dict(result="FALSE_POSITIVE", type=None, label=None, reviewer="李合规",
                         comment="引述客户投诉内容，非坐席本人违规，判为误报。", at=ts(4, 14, 2))),
        dict(id=1009, name="2026-09-15_10-11_inbound_7440.mp3", size=733_881, dur=92, status="COMPLIANT",
             up=ts(5, 10, 11),
             text="I can see the payment was declined by the issuing bank, so nothing has been charged. "
                  "Let me walk you through the alternatives and we can try again whenever you are ready."),
        dict(id=1010, name="2026-09-20_11-20_inbound_8860.mp3", size=1_655_320, dur=205, status="AI_CHECKING",
             up=ts(0, 11, 20),
             text="Look, I am not going to repeat myself. You are a moron and this whole company is full of shit. "
                  "Give me the supervisor now or I will make sure you regret it."),
        dict(id=1011, name="2026-09-14_17-40_inbound_7390.mp3", size=3_112_044, dur=0, status="FAILED",
             up=ts(6, 17, 40), text=None,
             error="Whisper 转写失败 (HTTP 400): Audio file could not be decoded. Please check the file format."),
        dict(id=1012, name="2026-09-20_11-31_inbound_8863.mp3", size=1_204_788, dur=None, status="PENDING",
             up=ts(0, 11, 31), text=None),
    ]


def generate_sql(api: str) -> int:
    now = datetime.now()
    records = build_records(now)
    lines = ["SET NAMES utf8mb4;",
             f"DELETE FROM pipeline_logs WHERE recording_id >= {ID_BASE};",
             f"DELETE FROM recordings WHERE id >= {ID_BASE};"]
    hit_total = 0

    for r in records:
        hits = hits_for(api, r["text"]) if r["text"] else []
        hit_total += len(hits)
        hit_json = json.dumps(hits, ensure_ascii=False)
        ai_json = json.dumps(r["ai"], ensure_ascii=False) if r.get("ai") else None
        review = r.get("review") or {}
        processed = None if r["status"] == "PENDING" else r["up"]
        duration = "NULL" if r["dur"] is None else r["dur"]

        lines.append(
            "INSERT INTO recordings (id, file_name, file_path, file_size, mime_type, duration_seconds, language, status, "
            "transcript, segments_json, dfa_hits_json, hit_count, ai_result_json, review_result, violation_type, "
            "violation_type_label, review_comment, reviewer, review_time, upload_time, processed_time, error_message) VALUES ("
            f"{r['id']}, {esc(r['name'])}, {esc('demo-' + str(r['id']) + '.mp3')}, {r['size']}, 'audio/mpeg', "
            f"{duration}, {esc('en')}, {esc(r['status'])}, {esc(r['text'])}, {esc('[]')}, {esc(hit_json)}, {len(hits)}, "
            f"{esc(ai_json)}, {esc(review.get('result'))}, {esc(review.get('type'))}, {esc(review.get('label'))}, "
            f"{esc(review.get('comment'))}, {esc(review.get('reviewer'))}, {esc(review.get('at'))}, "
            f"{esc(r['up'])}, {esc(processed)}, {esc(r.get('error'))});"
        )

        logs = [(r["up"], "PIPELINE", "INFO", "开始处理，待执行步骤: 转写 -> DFA 初筛 -> AI 复筛")]
        if r["text"]:
            logs.append((r["up"], "TRANSCRIBE", "INFO",
                         f"转写完成：时长 {r['dur']}s，语言 en，文本 {len(r['text'])} 字符"))
            if hits:
                words = ", ".join(sorted({h["word"] for h in hits}))
                logs.append((r["up"], "DFA", "WARN", f"DFA 初筛命中 {len(hits)} 处（{words}），进入 AI 语义复筛"))
            else:
                logs.append((r["up"], "DFA", "INFO", "DFA 初筛未命中敏感词"))
        if r["status"] in ("NEEDS_REVIEW", "AI_CHECKING") and r.get("ai"):
            a = r["ai"]
            logs.append((r["up"], "AI", "ERROR",
                         f"AI 复筛判定违规（{a['violationTypeLabel']}，置信度 {a['confidence']:.2f}）"
                         f"：{a['reason']} → 进入人工复检"))
        if r["status"] == "FAILED":
            logs.append((r["up"], "PIPELINE", "ERROR", f"处理失败: {r['error']}"))
        if review.get("result"):
            logs.append((review["at"], "REVIEW", "WARN" if review["result"] == "CONFIRMED_VIOLATION" else "INFO",
                         ("人工复检确认违规（" + (review.get("label") or "") + "）：" + review["comment"])
                         if review["result"] == "CONFIRMED_VIOLATION"
                         else ("人工复检判定为误报：" + review["comment"])))
        for t, stage, level, msg in logs:
            lines.append(
                "INSERT INTO pipeline_logs (recording_id, stage, level, message, created_at) VALUES ("
                f"{r['id']}, {esc(stage)}, {esc(level)}, {esc(msg)}, {esc(t)});"
            )

    SQL_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"已生成 {len(records)} 条演示录音（DFA 命中 {hit_total} 处）→ {SQL_FILE}")
    return len(records)


def apply_sql(container: str, clean_only: bool) -> None:
    if clean_only:
        sql = f"DELETE FROM pipeline_logs WHERE recording_id >= {ID_BASE}; DELETE FROM recordings WHERE id >= {ID_BASE};"
        subprocess.run(
            ["docker", "exec", container, "mysql", f"-u{MYSQL_USER}", f"-p{MYSQL_PASSWORD}", MYSQL_DB, "-e", sql],
            check=True, capture_output=True,
        )
        print(f"已清理演示数据（id >= {ID_BASE}）")
        return
    with SQL_FILE.open("rb") as f:
        subprocess.run(
            ["docker", "exec", "-i", container, "mysql", f"-u{MYSQL_USER}", f"-p{MYSQL_PASSWORD}", MYSQL_DB],
            stdin=f, check=True, capture_output=True,
        )
    print("演示数据已写入数据库")


def main():
    parser = argparse.ArgumentParser(description="生成/清理录音稽核演示数据")
    parser.add_argument("--api", default="http://localhost:8082", help="后端地址（用于计算 DFA 命中）")
    parser.add_argument("--container", default="record-audit-mysql", help="MySQL 容器名")
    parser.add_argument("--clean", action="store_true", help="仅清理演示数据")
    args = parser.parse_args()

    if args.clean:
        apply_sql(args.container, clean_only=True)
        return
    generate_sql(args.api)
    apply_sql(args.container, clean_only=False)


if __name__ == "__main__":
    main()
