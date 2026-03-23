"""
提取多个真实会话用于测试长对话处理方案
"""
import json
import os
import psycopg2

def extract_claude_session(session_prefix):
    """提取Claude Code会话"""
    history_path = os.path.expanduser('~/.claude/history.jsonl')
    with open(history_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    
    msgs = []
    for line in lines:
        data = json.loads(line)
        if data.get('sessionId', '').startswith(session_prefix):
            msgs.append({
                'role': 'user',
                'content': data.get('display', '')[:500]
            })
    return msgs

def extract_iflow_session(project, session_prefix):
    """提取iFlow会话"""
    iflow_path = os.path.expanduser(f'~/.iflow/projects/{project}')
    for file in os.listdir(iflow_path):
        if file.startswith(session_prefix):
            file_path = os.path.join(iflow_path, file)
            with open(file_path, 'r', encoding='utf-8') as f:
                lines = f.readlines()
            
            msgs = []
            for line in lines:
                try:
                    data = json.loads(line)
                    msg_type = data.get('type', '')
                    content = ''
                    if 'message' in data and isinstance(data['message'], dict):
                        c = data['message'].get('content', '')
                        if isinstance(c, str):
                            content = c[:500]
                        elif isinstance(c, list):
                            for part in c:
                                if isinstance(part, dict) and 'text' in part:
                                    content = part['text'][:500]
                                    break
                    msgs.append({
                        'role': 'assistant' if msg_type == 'assistant' else 'user',
                        'content': content
                    })
                except:
                    pass
            return msgs
    return []

def analyze_session(msgs, name):
    """分析会话，提取关键节点"""
    print(f"\n{'='*70}")
    print(f"=== {name} ({len(msgs)} 条消息) ===")
    print('='*70)
    
    # 关键词检测
    error_markers = ['不行', '报错', '失败', '错误', 'Error', 'exception', '还是不行']
    resolved_markers = ['好了', '成功', '解决', '搞定', '可以了', '修好了', '这样就行']
    give_up_markers = ['放弃', '换', '删掉', '不用', '算了']
    
    key_nodes = []
    for i, msg in enumerate(msgs):
        content = msg['content']
        for marker in error_markers:
            if marker in content:
                key_nodes.append((i+1, 'ERROR', marker, content[:80]))
                break
        for marker in resolved_markers:
            if marker in content:
                key_nodes.append((i+1, 'RESOLVED', marker, content[:80]))
                break
        for marker in give_up_markers:
            if marker in content:
                key_nodes.append((i+1, 'GIVE_UP', marker, content[:80]))
                break
    
    print(f"\n关键节点 ({len(key_nodes)} 个):")
    for idx, node_type, marker, content in key_nodes[:15]:
        print(f"  #{idx:3d} [{node_type:8s}] '{marker}': {content}...")
    
    # 统计
    error_count = sum(1 for _, t, _, _ in key_nodes if t == 'ERROR')
    resolved_count = sum(1 for _, t, _, _ in key_nodes if t == 'RESOLVED')
    give_up_count = sum(1 for _, t, _, _ in key_nodes if t == 'GIVE_UP')
    
    print(f"\n统计: 错误{error_count}次, 解决{resolved_count}次, 放弃{give_up_count}次")
    
    return key_nodes

# ============ 提取并分析多个会话 ============
if __name__ == '__main__':
    sessions = []
    
    # 1. Claude - NapCatQQ调试会话 (错误调试模式)
    msgs = extract_claude_session('cfd4b0b6')
    if msgs:
        nodes = analyze_session(msgs, 'Claude - NapCatQQ调试')
        sessions.append(('NapCatQQ调试', msgs, nodes))
    
    # 2. Claude - IBD项目 (需求迭代模式)
    msgs = extract_claude_session('abdd3f65')
    if msgs:
        nodes = analyze_session(msgs, 'Claude - IBD项目开发')
        sessions.append(('IBD项目开发', msgs, nodes))
    
    # 3. Claude - 手势特效 (效果调优模式)
    msgs = extract_claude_session('05cee62b')
    if msgs:
        nodes = analyze_session(msgs, 'Claude - 手势特效调优')
        sessions.append(('手势特效调优', msgs, nodes))
    
    # 4. iFlow - HeroBattle (大型项目)
    msgs = extract_iflow_session('-D-HeroBattle', 'session-765a4d5c')
    if msgs:
        nodes = analyze_session(msgs, 'iFlow - HeroBattle游戏开发')
        sessions.append(('HeroBattle开发', msgs, nodes))
    
    # 5. iFlow - AstrBot (部署问题)
    msgs = extract_iflow_session('-C-Users-31936-AstrBot', 'session-08c58f74')
    if msgs:
        nodes = analyze_session(msgs, 'iFlow - AstrBot启动问题')
        sessions.append(('AstrBot启动', msgs, nodes))
    
    # 总结
    print("\n" + "="*70)
    print("=== 测试会话总结 ===")
    print("="*70)
    for name, msgs, nodes in sessions:
        error_count = sum(1 for _, t, _, _ in nodes if t == 'ERROR')
        resolved_count = sum(1 for _, t, _, _ in nodes if t == 'RESOLVED')
        print(f"{name}: {len(msgs)}条消息, {error_count}错误, {resolved_count}解决")
    
    # 保存为JSON供后续测试
    output = []
    for name, msgs, nodes in sessions:
        output.append({
            'name': name,
            'total_messages': len(msgs),
            'key_nodes': [{'index': n[0], 'type': n[1], 'marker': n[2], 'content': n[3]} for n in nodes],
            'messages': msgs[:100]  # 保存前100条
        })
    
    with open('test_sessions.json', 'w', encoding='utf-8') as f:
        json.dump(output, f, ensure_ascii=False, indent=2)
    print(f"\n已保存到 test_sessions.json")
