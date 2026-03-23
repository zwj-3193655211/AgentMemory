"""对比改进前后的效果"""
import json
import re

with open('test_sessions.json', 'r', encoding='utf-8') as f:
    sessions = json.load(f)

def detect_old(content):
    """改进前：简单关键词匹配"""
    ERROR_MARKERS = ["不行", "报错", "失败", "错误", "异常", "崩溃", "Error", "Exception", "不能", "无法", "缺少", "找不到"]
    RESOLVED_MARKERS = ["好了", "成功", "解决", "搞定", "修好了", "改好了", "可以了", "这样就行", "原来", "是因为", "问题出在"]
    GIVE_UP_MARKERS = ["放弃", "换", "算了", "不搞", "跳过", "不用", "删掉"]
    PREFERENCE_MARKERS = ["我喜欢", "我习惯", "我偏好", "不用", "更偏向", "一般用"]
    PRACTICE_MARKERS = ["建议", "推荐", "注意", "记得", "有个坑", "踩坑", "经验是"]
    
    for m in RESOLVED_MARKERS:
        if m in content: return "RESOLVED"
    for m in GIVE_UP_MARKERS:
        if m in content: return "GIVE_UP"
    for m in ERROR_MARKERS:
        if m in content: return "ERROR"
    for m in PREFERENCE_MARKERS:
        if m in content: return "PREFERENCE"
    for m in PRACTICE_MARKERS:
        if m in content: return "PRACTICE"
    return None

def detect_new(content):
    """改进后：正则模式精确匹配"""
    MIN_LENGTH = 20
    if len(content) < MIN_LENGTH:
        return None
    
    EXCLUDE_PATTERNS = [r"你还记得"]
    for p in EXCLUDE_PATTERNS:
        if re.search(p, content): return None
    
    RESOLVED = [
        r"(成功|完成了?|搞定).{0,10}(启动|安装|配置|运行|修复|部署|导入)",
        r"(原来|后来发现|原因是).{3,50}",
        r"(好了|可以了|修好了|改好了).{0,20}$",
        r"(问题|错误|bug).{0,10}?(已|经).{0,5}(解决|修复|找到)",
        r"解决(使用?|方案)"
    ]
    for p in RESOLVED:
        if re.search(p, content): return "RESOLVED"
    
    GIVE_UP = [r"放弃(使用|了|这个)?", r"(算了|不搞了|先这样)", r"换成?.{0,10}(方案|方法|试试)"]
    for p in GIVE_UP:
        if re.search(p, content): return "GIVE_UP"
    
    PREFERENCE = [r"我(喜欢|习惯|偏好|更(喜欢|倾向)).{3,50}", r"我(一般|通常|平时).{0,5}(用|使).{3,30}"]
    for p in PREFERENCE:
        if re.search(p, content): return "PREFERENCE"
    
    PRACTICE = [r"(有个坑|踩坑)", r"(经验|建议|推荐).{3,50}", r"注意.{0,5}(要|别|不).{3,40}"]
    for p in PRACTICE:
        if re.search(p, content): return "PRACTICE"
    
    return None

print("=" * 70)
print("改进前后对比")
print("=" * 70)

total_msgs = 0
total_old = 0
total_new = 0

for s in sessions:
    msgs = [m.get("content", "") if isinstance(m, dict) else str(m) for m in s["messages"]]
    total_msgs += len(msgs)
    
    old_count = sum(1 for m in msgs if detect_old(m))
    new_count = sum(1 for m in msgs if detect_new(m))
    total_old += old_count
    total_new += new_count
    
    name = s["name"][:18]
    print(f"{name:20s}: {len(msgs):4d}条 -> 改进前{old_count:2d}条 -> 改进后{new_count:2d}条")

print("=" * 70)
print(f"总计: {total_msgs}条消息")
print(f"改进前: {total_old}条记忆 (压缩率{(total_msgs-total_old)/total_msgs*100:.1f}%)")
print(f"改进后: {total_new}条记忆 (压缩率{(total_msgs-total_new)/total_msgs*100:.1f}%)")
print()
print(f"改进效果: 减少了{total_old-total_new}条低质量记忆 ({(total_old-total_new)/total_old*100:.0f}%误判)")
