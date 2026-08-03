import json
import os

# 读取 Claude history
print("=" * 60)
print("=== Claude Code 历史记录 ===")
print("=" * 60)

history_path = os.path.expanduser("~/.claude/history.jsonl")
with open(history_path, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 按会话分组
sessions = {}
for line in lines:
    data = json.loads(line)
    sid = data.get('sessionId', 'unknown')
    if sid not in sessions:
        sessions[sid] = []
    sessions[sid].append(data)

# 找最长的几个会话
sorted_sessions = sorted(sessions.items(), key=lambda x: len(x[1]), reverse=True)

print(f"总会话数: {len(sessions)}")
print(f"消息总数: {len(lines)}")
print()

# 展示最长的3个会话
for sid, msgs in sorted_sessions[:3]:
    print(f"\n--- 会话 {sid[:8]}... ({len(msgs)}条消息) ---")
    for msg in msgs[:15]:  # 展示前15条
        display = msg.get('display', '')[:100]
        print(f"  用户: {display}")
    if len(msgs) > 15:
        print(f"  ... 还有 {len(msgs)-15} 条消息")

# 读取 iFlow 会话
print("\n" + "=" * 60)
print("=== iFlow CLI 会话 ===")
print("=" * 60)

iflow_path = os.path.expanduser("~/.iflow/projects")
for project_dir in os.listdir(iflow_path)[:3]:
    project_path = os.path.join(iflow_path, project_dir)
    if os.path.isdir(project_path):
        for file in os.listdir(project_path):
            if file.endswith('.jsonl'):
                file_path = os.path.join(project_path, file)
                with open(file_path, 'r', encoding='utf-8') as f:
                    iflow_lines = f.readlines()
                
                if len(iflow_lines) > 5:
                    print(f"\n--- {project_dir}/{file[:30]}... ({len(iflow_lines)}条) ---")
                    for line in iflow_lines[:10]:
                        try:
                            data = json.loads(line)
                            msg_type = data.get('type', '?')
                            content = ''
                            if 'message' in data and isinstance(data['message'], dict):
                                c = data['message'].get('content', '')
                                if isinstance(c, str):
                                    content = c[:80]
                                elif isinstance(c, list):
                                    for part in c:
                                        if isinstance(part, dict) and 'text' in part:
                                            content = part['text'][:80]
                                            break
                            print(f"  [{msg_type}] {content}...")
                        except:
                            pass
                    break
