# 演示数据（2026-10）

线上演示实例中预置了 9 条**合成的中文通话录音**（ID 1001–1009），用于截图、演示与验收：

- 3 条待复检：坐席辱骂、诈骗诱导、隐私索要
- 2 条已确认违规：辱骂 / 诈骗（含复核历史）
- 1 条误报、1 条合规（抽样）、1 条处理失败、1 条待处理
- 1001 为双声道演示（左=坐席、右=客户），含混音与分轨音频

## 生成方式

`make_demo.py` 使用 macOS `say`（中文 Tingting）逐句合成音频，并按真实时间轴拼装：

```bash
python3 tools/demo/make_demo.py
# 输出：/tmp/echolint-demo/audio/*.mp3 与 /tmp/echolint-demo/seed.sql
```

然后把音频放入后端上传卷、执行 SQL 即可：

```bash
VOL=$(docker volume inspect -f '{{.Mountpoint}}' echolint_uploads)
cp /tmp/echolint-demo/audio/*.mp3 "$VOL"/
docker exec -i -e MYSQL_PWD=<root密码> gx-mysql mysql -uroot --default-character-set=utf8mb4 echolint < /tmp/echolint-demo/seed.sql
```

> 演示数据直接写库（不经过上传接口），因此不依赖 ASR 服务；线上截图与 PDF 均基于这批数据。

## 清理

```sql
DELETE FROM pipeline_logs   WHERE recording_id BETWEEN 1001 AND 1099;
DELETE FROM review_records  WHERE recording_id BETWEEN 1001 AND 1099;
DELETE FROM ai_invocations  WHERE recording_id BETWEEN 1001 AND 1099;
DELETE FROM pipeline_tasks  WHERE recording_id BETWEEN 1001 AND 1099;
DELETE FROM recordings      WHERE id BETWEEN 1001 AND 1099;
DELETE FROM knowledge_entries WHERE created_by = 'demo';
```

并删除上传卷中的 `demo-*.mp3`（共 11 个文件）。

## 相关交付物

- 截图：`docs/screenshots/demo-2026-10/`（13 张：工作台、录音、详情、复检、报表、成本、任务、审计、知识库、AI 治理、大屏、英文界面、移动端）
- 项目介绍 PDF：`docs/EchoLint-项目介绍.pdf`
