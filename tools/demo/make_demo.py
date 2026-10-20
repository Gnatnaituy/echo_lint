#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate Chinese demo recordings (audio + SQL) for EchoLint screenshots/PDF.

- TTS via macOS `say` (Tingting / Eddy), assembled sentence-by-sentence so
  segment timestamps match the audio.
- 1001 is a two-channel (stereo) call to showcase the dialogue view.
- Emits /tmp/echolint-demo/seed.sql and /tmp/echolint-demo/audio/*.mp3
"""
import json
import math
import shutil
import subprocess
import wave
from array import array
from pathlib import Path

BASE = Path("/tmp/echolint-demo")
AUDIO = BASE / "audio"
RATE = 22050
LEAD = 0.35
GAP = 0.28
VOICE_F = "Tingting"
VOICE_M = "Tingting"
VOICE_F2 = "Tingting"
VOICE_M2 = "Tingting"

AUDIO.mkdir(parents=True, exist_ok=True)
for f in AUDIO.glob("*"):
    f.unlink()

def run(cmd):
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def synth(text, voice, out_wav: Path):
    aiff = out_wav.with_suffix(".aiff")
    run(["say", "-v", voice, "-r", "190", "-o", str(aiff), text])
    run(["ffmpeg", "-y", "-i", str(aiff), "-ac", "1", "-ar", str(RATE),
         "-c:a", "pcm_s16le", str(out_wav)])
    aiff.unlink(missing_ok=True)
    with wave.open(str(out_wav), "rb") as w:
        data = array("h")
        data.frombytes(w.readframes(w.getnframes()))
    return data

def write_wav(path: Path, samples: array):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(samples.tobytes())

def to_mp3(wav: Path, mp3: Path):
    run(["ffmpeg", "-y", "-i", str(wav), "-codec:a", "libmp3lame", "-b:a", "64k", str(mp3)])
    return mp3.stat().st_size

def add_at(buf: array, samples: array, offset: int):
    need = offset + len(samples)
    if need > len(buf):
        buf.extend([0] * (need - len(buf)))
    for i, v in enumerate(samples):
        j = offset + i
        mixed = buf[j] + v
        buf[j] = 32767 if mixed > 32767 else (-32768 if mixed < -32768 else mixed)

def build_audio(sc, sid):
    """Return (segments, duration_s, files_info)."""
    cursor = LEAD
    timeline = []
    for i, (speaker, text, voice) in enumerate(sc["sentences"]):
        wav = AUDIO / f"tmp-{sid}-{i}.wav"
        samples = synth(text, voice, wav)
        dur = len(samples) / RATE
        timeline.append((speaker, text, cursor, cursor + dur, samples))
        cursor += dur + GAP
    total = cursor + 0.4
    n = int(math.ceil(total * RATE))
    files = {}
    if sc.get("stereo"):
        left = array("h", [0] * n)
        right = array("h", [0] * n)
        for speaker, text, s, e, samples in timeline:
            target = left if speaker == "坐席" else right
            add_at(target, samples, int(round(s * RATE)))
        mix = array("h", [0] * n)
        for i in range(n):
            v = left[i] + right[i]
            mix[i] = 32767 if v > 32767 else (-32768 if v < -32768 else v)
        for name, buf in (("L", left), ("R", right), ("mix", mix)):
            wav = AUDIO / f"tmp-{sid}-{name}.wav"
            write_wav(wav, buf)
            mp3 = AUDIO / (f"demo-{sid}.mp3" if name == "mix" else f"demo-{sid}.{name}.mp3")
            files[name] = {"name": mp3.name, "size": to_mp3(wav, mp3)}
            wav.unlink(missing_ok=True)
        file_path = files["mix"]["name"]
        channel_files = {"L": files["L"]["name"], "R": files["R"]["name"]}
    else:
        mono = array("h", [0] * n)
        for speaker, text, s, e, samples in timeline:
            add_at(mono, samples, int(round(s * RATE)))
        wav = AUDIO / f"tmp-{sid}.wav"
        write_wav(wav, mono)
        mp3 = AUDIO / f"demo-{sid}.mp3"
        files["mix"] = {"name": mp3.name, "size": to_mp3(wav, mp3)}
        wav.unlink(missing_ok=True)
        file_path = mp3.name
        channel_files = None
    for f in AUDIO.glob(f"tmp-{sid}-*.wav"):
        f.unlink(missing_ok=True)
    segments = []
    for i, (speaker, text, s, e, _) in enumerate(timeline):
        seg = {"id": i, "start": round(s, 2), "end": round(e, 2), "text": text}
        if sc.get("stereo"):
            seg["speaker"] = speaker
            seg["channel"] = "L" if speaker == "坐席" else "R"
        segments.append(seg)
    duration = int(round(total))
    transcript = "".join(t for _, t, _, _, _ in timeline)
    return segments, duration, transcript, file_path, files["mix"]["size"], channel_files

def find_hits(transcript, words):
    hits = []
    for w in words:
        start = 0
        while True:
            idx = transcript.find(w, start)
            if idx < 0:
                break
            hits.append({"word": w, "start": idx, "end": idx + len(w)})
            start = idx + 1
    hits.sort(key=lambda h: h["start"])
    return hits

def ai_json(sc):
    ai = sc.get("ai")
    if not ai:
        return None
    return {
        "violation": ai["violation"],
        "violationType": ai["type"],
        "violationTypeLabel": ai["label"],
        "reason": ai["reason"],
        "confidence": ai["confidence"],
        "targetSentence": ai.get("target", ""),
        "screenProvider": "deepseek",
        "screenProviderLabel": "DeepSeek",
        "screenModel": "deepseek-flash",
        "degraded": False,
        "screenVariant": "A",
    }

SCENARIOS = [
    dict(
        id=1001, stereo=True,
        file_name="2026-10-20_10-14_呼入_张伟_退款售后.mp3",
        agent="张伟", team="售后一组", direction="INBOUND", business="退款售后",
        minutes_ago=95, priority="HIGH", assigned="admin", sampled=0,
        status="NEEDS_REVIEW", words=["傻逼", "弄死你"],
        sentences=[
            ("坐席", "您好，这里是客服中心，请问有什么可以帮您？", VOICE_F),
            ("客户", "我的订单退款已经拖了三天了，你们到底什么时候处理？", VOICE_M),
            ("坐席", "您先别着急，我再帮您查一下订单状态。", VOICE_F),
            ("客户", "每次都说查，我已经等了很久了！", VOICE_M),
            ("坐席", "你这个傻逼客户，跟你说了多少遍了，再催我就弄死你。", VOICE_F),
            ("客户", "你怎么骂人？我要投诉你！", VOICE_M),
        ],
        ai=dict(violation=True, type="INSULT", label="辱骂", confidence=0.96,
                target="你这个傻逼客户，跟你说了多少遍了，再催我就弄死你。",
                reason="坐席在退款沟通中直接辱骂客户并使用人身威胁，构成服务红线违规。"),
    ),
    dict(
        id=1002, stereo=False,
        file_name="2026-10-20_09-42_呼出_李娜_会员回访.mp3",
        agent="李娜", team="电销二组", direction="OUTBOUND", business="会员回访",
        minutes_ago=260, priority="HIGH", assigned=None, sampled=0,
        status="NEEDS_REVIEW", words=["短信验证码", "安全账户"],
        sentences=[
            ("坐席", "姐，这个活动今天是最后一天了，您把收到的短信验证码告诉我，我帮您操作返现。", VOICE_F2),
            ("坐席", "您先把钱转到我们的安全账户，十分钟后连本带利一起退给您。", VOICE_F2),
            ("客户", "这样安全吗？我有点不放心。", VOICE_M),
        ],
        ai=dict(violation=True, type="FRAUD", label="诈骗诱导", confidence=0.94,
                target="您先把钱转到我们的安全账户，十分钟后连本带利一起退给您。",
                reason="以返现为名索要短信验证码，并诱导客户向所谓安全账户转账，符合诈骗话术特征。"),
    ),
    dict(
        id=1003, stereo=False,
        file_name="2026-10-19_16-08_呼入_王强_账号安全.mp3",
        agent="王强", team="账户安全组", direction="INBOUND", business="账号安全",
        minutes_ago=1260, priority="NORMAL", assigned=None, sampled=0,
        status="NEEDS_REVIEW", words=["密码是多少", "家庭住址"],
        sentences=[
            ("坐席", "为了核实您的身份，请问您的银行卡密码是多少？", VOICE_M2),
            ("客户", "密码也要说吗？", VOICE_M),
            ("坐席", "对，还有您的家庭住址也报一下，我这边帮您登记。", VOICE_M2),
        ],
        ai=dict(violation=True, type="PRIVACY", label="隐私泄露", confidence=0.91,
                target="为了核实您的身份，请问您的银行卡密码是多少？",
                reason="坐席以核实身份为由索要银行卡密码与家庭住址，属于违规收集敏感个人信息。"),
    ),
    dict(
        id=1004, stereo=False,
        file_name="2026-10-17_11-26_呼入_张伟_退款售后.mp3",
        agent="张伟", team="售后一组", direction="INBOUND", business="退款售后",
        minutes_ago=4320, priority="HIGH", assigned=None, sampled=0,
        status="VIOLATION_CONFIRMED", words=["傻逼", "弄死你"],
        confirmed=("CONFIRMED_VIOLATION", "INSULT", "辱骂",
                   "已回听录音，坐席存在辱骂与威胁客户行为，按服务红线处理。", "刘主管", 180),
        sentences=[
            ("坐席", "我说了多少次了，你这个傻逼，别再来烦我。", VOICE_F),
            ("客户", "你作为客服怎么能这样说话？", VOICE_M),
            ("坐席", "再打电话来我就弄死你，听见没有？", VOICE_F),
        ],
        ai=dict(violation=True, type="INSULT", label="辱骂", confidence=0.97,
                target="我说了多少次了，你这个傻逼，别再来烦我。",
                reason="坐席多次辱骂客户并使用人身威胁，情节严重。"),
    ),
    dict(
        id=1005, stereo=False,
        file_name="2026-10-16_15-03_呼出_李娜_会员回访.mp3",
        agent="李娜", team="电销二组", direction="OUTBOUND", business="会员回访",
        minutes_ago=5760, priority="HIGH", assigned=None, sampled=0,
        status="VIOLATION_CONFIRMED", words=["内部消息", "安全账户"],
        confirmed=("CONFIRMED_VIOLATION", "FRAUD", "诈骗诱导",
                   "确认存在内幕消息诱导与安全账户转账话术，已按诈骗风险上报。", "刘主管", 240),
        sentences=[
            ("坐席", "哥，我有内部消息，这只股票下周肯定翻倍，你把钱转到安全账户，我带你操作。", VOICE_F2),
            ("客户", "真的假的？不会是骗人的吧。", VOICE_M),
            ("坐席", "放心，稳赚不赔，错过就没了。", VOICE_F2),
        ],
        ai=dict(violation=True, type="FRAUD", label="诈骗诱导", confidence=0.93,
                target="哥，我有内部消息，这只股票下周肯定翻倍，你把钱转到安全账户，我带你操作。",
                reason="以内部消息、稳赚不赔为诱饵，引导客户向安全账户转账，典型诈骗话术。"),
    ),
    dict(
        id=1006, stereo=False,
        file_name="2026-10-15_09-51_呼入_赵敏_物流查询.mp3",
        agent="赵敏", team="售后二组", direction="INBOUND", business="物流查询",
        minutes_ago=7200, priority="NORMAL", assigned=None, sampled=0,
        status="FALSE_POSITIVE", words=["妈的"],
        confirmed=("FALSE_POSITIVE", None, None,
                   "客户先使用不当用语，坐席复述原话进行确认，无主观侮辱意图，判定误报。", "陈质检", 120),
        sentences=[
            ("客户", "妈的，我的快递到底什么时候能到？", VOICE_M),
            ("坐席", "您刚才说“妈的”，我理解您很着急，我马上帮您查物流。", VOICE_F2),
            ("客户", "对，赶紧的。", VOICE_M),
        ],
        ai=dict(violation=True, type="INSULT", label="辱骂", confidence=0.61,
                target="妈的，我的快递到底什么时候能到？",
                reason="转写中出现侮辱性用语，需人工确认说话人与语境。"),
    ),
    dict(
        id=1007, stereo=False,
        file_name="2026-10-14_14-18_呼入_赵敏_退款售后.mp3",
        agent="赵敏", team="售后二组", direction="INBOUND", business="退款售后",
        minutes_ago=8640, priority="LOW", assigned=None, sampled=1,
        status="COMPLIANT", words=[],
        sentences=[
            ("坐席", "您好，您的退款申请已经提交，三个工作日内会原路退回。", VOICE_F2),
            ("客户", "好的，谢谢。", VOICE_M),
            ("坐席", "不客气，有问题随时联系我们。", VOICE_F2),
        ],
        ai=dict(violation=False, type="NONE", label="无", confidence=0.88,
                target="", reason="全程为正常退款流程沟通，未发现辱骂、威胁或诱导行为。"),
    ),
    dict(
        id=1008, stereo=False,
        file_name="2026-10-19_18-36_呼入_孙悦_投诉处理.mp3",
        agent="孙悦", team="质检支持组", direction="INBOUND", business="投诉处理",
        minutes_ago=1500, priority="NORMAL", assigned=None, sampled=0,
        status="FAILED", words=[],
        error="转写服务调用超时（600s），已自动重试 3 次，任务进入死信队列",
        sentences=[
            ("坐席", "您好，您的投诉我们已经记录，稍后会有专人联系您。", VOICE_F),
            ("客户", "好的，希望你们尽快处理。", VOICE_M),
        ],
    ),
    dict(
        id=1009, stereo=False,
        file_name="2026-10-20_12-10_呼入_周杰_退款售后.mp3",
        agent="周杰", team="售后一组", direction="INBOUND", business="退款售后",
        minutes_ago=8, priority="NORMAL", assigned=None, sampled=0,
        status="PENDING", words=[],
        sentences=[
            ("坐席", "您好，请问是周先生吗？您的退款申请我们已经收到。", VOICE_M2),
            ("客户", "是我，大概什么时候能到账？", VOICE_M),
        ],
    ),
]

def sql_str(s):
    if s is None:
        return "NULL"
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"

def js(obj):
    return sql_str(json.dumps(obj, ensure_ascii=False, separators=(",", ":")))

lines = [
    "SET NAMES utf8mb4;",
    "DELETE FROM pipeline_logs WHERE recording_id BETWEEN 1001 AND 1099;",
    "DELETE FROM review_records WHERE recording_id BETWEEN 1001 AND 1099;",
    "DELETE FROM ai_invocations WHERE recording_id BETWEEN 1001 AND 1099;",
    "DELETE FROM pipeline_tasks WHERE recording_id BETWEEN 1001 AND 1099;",
    "DELETE FROM recordings WHERE id BETWEEN 1001 AND 1099;",
    "DELETE FROM knowledge_entries WHERE created_by = 'demo';",
    "UPDATE ai_providers SET input_price_per_1k=0.00027, output_price_per_1k=0.0011 WHERE provider_key='deepseek';",
]

manifest = []
for idx, sc in enumerate(SCENARIOS):
    sid = sc["id"]
    segments, duration, transcript, file_path, size, channel_files = build_audio(sc, sid)
    hits = find_hits(transcript, sc["words"])
    ai = ai_json(sc)
    upload = f"DATE_SUB(NOW(), INTERVAL {sc['minutes_ago']} MINUTE)"
    processed = f"DATE_ADD({upload}, INTERVAL {5 + idx} SECOND)" if sc["status"] not in ("PENDING",) else "NULL"
    call_time = f"DATE_SUB({upload}, INTERVAL 3 MINUTE)"
    review_count = 1 if sc.get("confirmed") else 0
    review_result = None
    reviewer = None
    violation_type = None
    violation_label = None
    review_comment = None
    review_time = "NULL"
    if sc.get("confirmed"):
        cresult, ctype, clabel, comment, rev, mins = sc["confirmed"]
        review_result = cresult
        review_comment = comment
        reviewer = rev
        review_time = f"DATE_ADD({upload}, INTERVAL {mins} MINUTE)"
        if cresult == "CONFIRMED_VIOLATION":
            violation_type = ctype
            violation_label = clabel
    if sc["status"] == "VIOLATION_CONFIRMED":
        violation_type = sc["confirmed"][1]
        violation_label = sc["confirmed"][2]
    elif sc["status"] == "FALSE_POSITIVE":
        violation_type = None
        violation_label = None

    columns = ("id,file_name,file_path,file_size,mime_type,duration_seconds,language,status,transcript,segments_json,"
               "channel_count,channel_files_json,dfa_hits_json,hit_count,ai_result_json,review_result,"
               "violation_type,violation_type_label,review_comment,reviewer,review_time,upload_time,processed_time,"
               "status_updated_at,error_message,agent_name,team_name,call_time,direction,business_line,priority,"
               "assigned_to,review_count,sampled,audio_sha256")
    values = ",".join([
        str(sid), sql_str(sc["file_name"]), sql_str(file_path), str(size), sql_str("audio/mpeg"),
        str(duration), sql_str("zh"), sql_str(sc["status"]),
        sql_str(transcript) if sc["status"] not in ("PENDING", "FAILED") else "NULL",
        js(segments) if sc["status"] not in ("PENDING", "FAILED") else "NULL",
        "2" if sc.get("stereo") else "1",
        js(channel_files) if channel_files else "NULL",
        js(hits), str(len(hits)),
        js(ai) if (ai and sc["status"] not in ("PENDING", "FAILED")) else "NULL",
        sql_str(review_result) if review_result else "NULL",
        sql_str(violation_type) if violation_type else "NULL",
        sql_str(violation_label) if violation_label else "NULL",
        sql_str(review_comment) if review_comment else "NULL",
        sql_str(reviewer) if reviewer else "NULL",
        review_time,
        upload, processed if processed != "NULL" else "NULL",
        processed if processed != "NULL" else upload,
        sql_str(sc.get("error")) if sc.get("error") else "NULL",
        sql_str(sc["agent"]), sql_str(sc["team"]), call_time, sql_str(sc["direction"]),
        sql_str(sc["business"]), sql_str(sc["priority"]),
        sql_str(sc["assigned"]) if sc["assigned"] else "NULL",
        str(review_count), str(sc["sampled"]), "NULL",
    ])
    lines.append(f"INSERT INTO recordings ({columns}) VALUES ({values});")

    # pipeline logs
    if sc["status"] != "PENDING":
        logs = [("PIPELINE", "INFO", "开始处理，待执行步骤: 转写 -> DFA 初筛 -> AI 复筛", 1)]
        if sc["status"] == "FAILED":
            logs.append(("PIPELINE", "ERROR", sc["error"], 2))
        else:
            logs += [
                ("TRANSCRIBE", "INFO", f"转写完成：时长 {duration}s，语言 zh，分段 {len(segments)} 段", 2),
                ("DFA", "WARN" if hits else "INFO",
                 ("DFA 初筛命中 " + str(len(hits)) + " 处（" + "、".join(sorted({h['word'] for h in hits})) + "），进入 AI 语义复筛")
                 if hits else "DFA 初筛未命中敏感词，自动通过" if not sc["sampled"] else "抽样复核：DFA 初筛未命中敏感词，进入 AI 语义复筛",
                 3),
            ]
            if ai:
                logs.append(("AI", "ERROR" if ai["violation"] else "INFO",
                             f"AI 复筛{'判定违规' if ai['violation'] else '判定合规'}（置信度 {ai['confidence']:.2f}，模型 DeepSeek · deepseek-flash）",
                             4))
            logs.append(("PIPELINE", "INFO",
                         "归档为 " + ("COMPLIANT" if sc["status"] in ("COMPLIANT", "FALSE_POSITIVE") else "VIOLATION_CONFIRMED" if sc["status"] == "VIOLATION_CONFIRMED" else "NEEDS_REVIEW"),
                         5))
        for stage, level, msg, secs in logs:
            lines.append(
                "INSERT INTO pipeline_logs (recording_id,stage,level,message,created_at) VALUES ("
                f"{sid},{sql_str(stage)},{sql_str(level)},{sql_str(msg)},DATE_ADD({upload}, INTERVAL {secs} SECOND));")

    # review records
    if sc.get("confirmed"):
        cresult, ctype, clabel, comment, rev, mins = sc["confirmed"]
        review_vtype = ctype if cresult == "CONFIRMED_VIOLATION" else None
        lines.append(
            "INSERT INTO review_records (recording_id,reviewer_id,reviewer_username,result,violation_type,comment,round,created_at) VALUES ("
            f"{sid},NULL,{sql_str(rev)},{sql_str(cresult)},{sql_str(review_vtype)},"
            f"{sql_str(comment)},1,DATE_ADD({upload}, INTERVAL {mins} MINUTE));")

    # ai invocation
    if ai and sc["status"] not in ("PENDING", "FAILED"):
        req = {"model": "deepseek-flash", "messages": [
            {"role": "system", "content": "你是呼叫中心录音的合规审查助手……"},
            {"role": "user", "content": "待审核转写文本（节选）：" + transcript[:80] + "……"}]}
        resp = {"violation": ai["violation"], "violation_type": ai["violationType"],
                "reason": ai["reason"], "confidence": ai["confidence"],
                "target_sentence": ai["targetSentence"]}
        in_tok = 900 + idx * 37
        out_tok = 160 + idx * 9
        cost = int((in_tok / 1000) * 0.00027 * 1_000_000 + (out_tok / 1000) * 0.0011 * 1_000_000)
        lines.append(
            "INSERT INTO ai_invocations (recording_id,stage,provider_id,provider_label,model,endpoint,request_json,response_json,"
            "success,error_message,latency_ms,request_chars,response_chars,degraded,created_at,input_tokens,output_tokens,cost_micros) VALUES ("
            f"{sid},'SCREEN','deepseek','DeepSeek','deepseek-flash','https://api.deepseek.com/chat/completions',"
            f"{js(req)},{js(resp)},1,NULL,{1200 + idx * 130},300,240,0,"
            f"DATE_ADD({upload}, INTERVAL {5 + idx} SECOND),{in_tok},{out_tok},{cost});")

    # task
    task_status = "SUCCEEDED" if sc["status"] not in ("PENDING", "FAILED") else ("DEAD" if sc["status"] == "FAILED" else "PENDING")
    attempts = 1 if task_status == "SUCCEEDED" else (3 if task_status == "DEAD" else 0)
    finished = f"DATE_ADD({upload}, INTERVAL {30 + idx} SECOND)" if task_status == "SUCCEEDED" else "NULL"
    last_err = sql_str(sc["error"]) if task_status == "DEAD" else "NULL"
    next_at = "NULL" if task_status != "PENDING" else "NOW()"
    lines.append(
        "INSERT INTO pipeline_tasks (recording_id,status,attempts,max_attempts,next_attempt_at,last_error,created_at,updated_at,finished_at) VALUES ("
        f"{sid},{sql_str(task_status)},{attempts},3,{next_at},{last_err},{upload},"
        f"{finished if finished != 'NULL' else upload},{finished});")

    manifest.append({"id": sid, "status": sc["status"], "duration": duration,
                     "file": file_path, "files": list(files.keys()) if False else None})

# knowledge entries
knowledge = [
    ("退款沟通红线话术", "服务规范", "INSULT", "退款、售后、投诉处理", "退款沟通中禁止辱骂、威胁客户；即使客户情绪激动，也应致歉、复述诉求并给出明确处理时限。出现人身攻击即触发服务红线。"),
    ("诈骗话术识别要点", "风险识别", "FRAUD", "诈骗诱导,安全账户,验证码", "典型特征：以返现/内幕消息为诱饵，索要短信验证码，诱导向“安全账户”转账，承诺稳赚不赔或限时名额。发现后立即转人工复核并上报。"),
    ("客户隐私信息处置", "合规要求", "PRIVACY", "隐私泄露,身份证,银行卡", "不得以核实身份为由索要银行卡密码、短信验证码等敏感信息；确需核验时仅采集最小必要字段，并全程留痕。"),
]
for title, cat, vt, tags, content in knowledge:
    lines.append(
        "INSERT INTO knowledge_entries (title,category,violation_type,tags,content,enabled,created_by,created_at,updated_at) VALUES ("
        f"{sql_str(title)},{sql_str(cat)},{sql_str(vt)},{sql_str(tags)},{sql_str(content)},1,'demo',NOW(),NOW());")

lines.append("ALTER TABLE recordings AUTO_INCREMENT = 1100;")
lines.append("ALTER TABLE pipeline_tasks AUTO_INCREMENT = 1100;")
lines.append("ALTER TABLE ai_invocations AUTO_INCREMENT = 1100;")
lines.append("ALTER TABLE review_records AUTO_INCREMENT = 1100;")

(BASE / "seed.sql").write_text("\n".join(lines) + "\n", encoding="utf-8")
(BASE / "manifest.json").write_text(json.dumps([m for m in manifest if True], ensure_ascii=False, indent=2), encoding="utf-8")
print("SQL lines:", len(lines))
for f in sorted(AUDIO.glob("*.mp3")):
    print(f"{f.name:24s} {f.stat().st_size/1024:7.1f} KiB")
print("seed.sql:", (BASE / "seed.sql").stat().st_size, "bytes")
