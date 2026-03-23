"""
测试长对话处理系统
使用真实会话数据验证 CompletionDetector, KeyNodeExtractor, SessionProcessor
"""
import json
from datetime import datetime, timedelta
from typing import List, Dict, Tuple

# ========== 模拟 Java 类的 Python 实现（用于测试）==========

class CompletionDetector:
    """完成信号检测器"""
    
    TIME_WINDOW = timedelta(minutes=5)
    MIN_MESSAGES = 3
    
    COMPLETION_MARKERS = ["好了", "搞定", "解决", "成功", "可以了", "完成了", "没问题了",
                          "修好了", "改好了", "这样就行", "行了", "完事", "告一段落"]
    
    INTERRUPTION_MARKERS = ["算了", "不搞了", "先这样", "暂时", "以后再说", "不做了",
                           "放弃", "换", "跳过", "先停", "先关"]
    
    AGENT_SUCCESS_MARKERS = ["已完成", "已修复", "已解决", "成功启动", "安装成功",
                             "配置完成", "部署成功", "测试通过"]
    
    @classmethod
    def detect(cls, messages: List[str], last_activity: datetime = None) -> Tuple[str, str]:
        """检测会话状态"""
        if not messages:
            return ("EMPTY", "会话为空")
        
        if len(messages) < cls.MIN_MESSAGES:
            return ("TOO_SHORT", f"消息太少({len(messages)}条)")
        
        last_msg = messages[-1]
        
        # 检查显式完成
        for marker in cls.COMPLETION_MARKERS:
            if marker in last_msg:
                return ("COMPLETED", f"检测到完成标记: {marker}")
        
        # 检查中断
        for marker in cls.INTERRUPTION_MARKERS:
            if marker in last_msg:
                return ("INTERRUPTED", f"检测到中断标记: {marker}")
        
        # 检查时间窗口
        if last_activity:
            elapsed = datetime.now() - last_activity
            if elapsed > cls.TIME_WINDOW:
                return ("TIMEOUT", f"时间窗口已过: {elapsed}")
        
        # 检查Agent成功标记
        for msg in messages[-3:]:
            for marker in cls.AGENT_SUCCESS_MARKERS:
                if marker in msg:
                    return ("COMPLETED", f"检测到Agent成功标记: {marker}")
        
        return ("ONGOING", "进行中")
    
    @classmethod
    def should_process_incrementally(cls, messages: List[str]) -> bool:
        return len(messages) >= 50


class KeyNodeExtractor:
    """关键节点提取器"""
    
    ERROR_MARKERS = ["不行", "报错", "失败", "错误", "异常", "崩溃", "Error",
                    "Exception", "不能", "无法", "缺少", "找不到"]
    
    RESOLVED_MARKERS = ["好了", "成功", "解决", "搞定", "修好了", "改好了",
                       "可以了", "这样就行", "原来", "是因为", "问题出在"]
    
    GIVE_UP_MARKERS = ["放弃", "换", "算了", "不搞", "跳过", "不用", "删掉"]
    
    PREFERENCE_MARKERS = ["我喜欢", "我习惯", "我偏好", "不用", "更偏向", "一般用"]
    
    PRACTICE_MARKERS = ["建议", "推荐", "注意", "记得", "有个坑", "踩坑", "经验是"]
    
    @classmethod
    def extract(cls, messages: List[str]) -> List[Dict]:
        """提取关键节点"""
        nodes = []
        
        for i, msg in enumerate(messages):
            if not msg or len(msg) < 10:
                continue
            
            # 检测各类型
            node = cls._detect_node(msg, i)
            if node:
                nodes.append(node)
        
        # 配对错误和解决
        nodes = cls._pair_error_resolved(nodes)
        
        return nodes
    
    @classmethod
    def _detect_node(cls, content: str, index: int) -> Dict:
        for marker in cls.RESOLVED_MARKERS:
            if marker in content:
                return {"index": index, "type": "RESOLVED", "marker": marker, "content": content[:100]}
        
        for marker in cls.GIVE_UP_MARKERS:
            if marker in content:
                return {"index": index, "type": "GIVE_UP", "marker": marker, "content": content[:100]}
        
        for marker in cls.ERROR_MARKERS:
            if marker in content:
                return {"index": index, "type": "ERROR", "marker": marker, "content": content[:100]}
        
        for marker in cls.PREFERENCE_MARKERS:
            if marker in content:
                return {"index": index, "type": "PREFERENCE", "marker": marker, "content": content[:100]}
        
        for marker in cls.PRACTICE_MARKERS:
            if marker in content:
                return {"index": index, "type": "PRACTICE", "marker": marker, "content": content[:100]}
        
        return None
    
    @classmethod
    def _pair_error_resolved(cls, nodes: List[Dict]) -> List[Dict]:
        """配对错误和解决节点"""
        for i, node in enumerate(nodes):
            if node["type"] == "ERROR":
                # 找后面的解决节点
                for j in range(i + 1, min(i + 5, len(nodes))):
                    if nodes[j]["type"] == "RESOLVED":
                        node["paired_resolved"] = j
                        nodes[j]["paired_error"] = i
                        break
        return nodes
    
    @classmethod
    def filter_worth_saving(cls, nodes: List[Dict]) -> List[Dict]:
        """过滤出值得保存的节点"""
        result = []
        for node in nodes:
            if node["type"] == "RESOLVED":
                result.append(node)
            elif node["type"] == "GIVE_UP" and node["marker"] in ["放弃", "换"]:
                result.append(node)
            elif node["type"] == "PREFERENCE":
                result.append(node)
            elif node["type"] == "PRACTICE":
                result.append(node)
            elif node["type"] == "ERROR" and "paired_resolved" in node:
                result.append(node)
        return result


# ========== 测试函数 ==========

def test_with_real_sessions():
    """用真实会话测试"""
    print("=" * 70)
    print("测试真实会话数据")
    print("=" * 70)
    
    # 加载测试数据
    with open("test_sessions.json", "r", encoding="utf-8") as f:
        sessions = json.load(f)
    
    total_extracted = 0
    total_worth_saving = 0
    
    for session in sessions:
        name = session["name"]
        total_msgs = session["total_messages"]
        # 消息是字典格式，提取content字段
        raw_messages = session["messages"]
        messages = [m.get("content", "") if isinstance(m, dict) else str(m) for m in raw_messages]
        
        print(f"\n--- {name} ({total_msgs}条消息) ---")
        
        # 1. 测试完成检测
        status, desc = CompletionDetector.detect(messages)
        print(f"状态检测: {status} - {desc}")
        
        # 2. 测试关键节点提取
        nodes = KeyNodeExtractor.extract(messages)
        worth_saving = KeyNodeExtractor.filter_worth_saving(nodes)
        
        print(f"关键节点: {len(nodes)}个, 值得保存: {len(worth_saving)}个")
        
        # 显示值得保存的节点
        for node in worth_saving[:5]:
            print(f"  #{node['index']+1} [{node['type']:8s}] '{node['marker']}': {node['content'][:50]}...")
        
        if len(worth_saving) > 5:
            print(f"  ... 还有 {len(worth_saving)-5} 个")
        
        total_extracted += len(nodes)
        total_worth_saving += len(worth_saving)
    
    # 总结
    print("\n" + "=" * 70)
    print("测试总结")
    print("=" * 70)
    print(f"总会话数: {len(sessions)}")
    print(f"总关键节点: {total_extracted}")
    print(f"总值得保存: {total_worth_saving}")
    
    # 计算压缩率
    total_input = sum(s["total_messages"] for s in sessions)
    print(f"总输入消息: {total_input}")
    print(f"压缩率: {(total_input - total_worth_saving) / total_input * 100:.1f}%")
    print(f"平均每会话保存: {total_worth_saving / len(sessions):.1f} 条记忆")


def test_incremental_processing():
    """测试增量处理"""
    print("\n" + "=" * 70)
    print("测试增量处理")
    print("=" * 70)
    
    # 模拟一个大型会话（600条消息）
    large_session = []
    for i in range(600):
        if i % 50 == 0:
            large_session.append(f"第{i}条: 终于成功了，问题解决了")  # 解决节点
        elif i % 30 == 0:
            large_session.append(f"第{i}条: 还是不行，继续尝试")  # 错误节点
        else:
            large_session.append(f"第{i}条: 普通消息内容...")
    
    # 检测是否应该增量处理
    should = CompletionDetector.should_process_incrementally(large_session)
    print(f"会话消息数: {len(large_session)}")
    print(f"应该增量处理: {should}")
    
    # 模拟增量处理
    batch_size = 50
    for batch_start in range(0, len(large_session), batch_size):
        batch = large_session[batch_start:batch_start + batch_size]
        nodes = KeyNodeExtractor.extract(batch)
        worth_saving = KeyNodeExtractor.filter_worth_saving(nodes)
        
        if worth_saving:
            print(f"  批次 {batch_start//batch_size + 1}: 提取 {len(worth_saving)} 条记忆")


def test_completion_signals():
    """测试各种完成信号"""
    print("\n" + "=" * 70)
    print("测试完成信号检测")
    print("=" * 70)
    
    test_cases = [
        (["帮我看看这个问题", "找到原因了", "好了修好了"], "显式完成标记"),
        (["试了好几次", "还是不行", "算了先这样吧"], "中断标记"),
        (["问题是什么", "让我查查", "已修复，请测试"], "Agent成功标记"),
        (["第一条", "第二条", "第三条"], "进行中（无信号）"),
        (["短的"], "消息太少"),
    ]
    
    for messages, desc in test_cases:
        status, reason = CompletionDetector.detect(messages)
        print(f"{desc}: {status} - {reason}")


def show_compressed_content():
    """展示压缩后的具体内容"""
    print("\n" + "=" * 70)
    print("压缩后的内容（将被保存为记忆）")
    print("=" * 70)
    
    with open("test_sessions.json", "r", encoding="utf-8") as f:
        sessions = json.load(f)
    
    for session in sessions:
        name = session["name"]
        raw_msgs = session["messages"]
        messages = [m.get("content", "") if isinstance(m, dict) else str(m) for m in raw_msgs]
        
        print(f"\n### {name}")
        
        saved = []
        for i, msg in enumerate(messages):
            if len(msg) < 10: continue
            t, marker = detect_type(msg)
            if t in ["RESOLVED", "GIVE_UP", "PREFERENCE", "PRACTICE"]:
                saved.append((i+1, t, marker, msg[:150]))
        
        for idx, t, marker, content in saved[:5]:
            print(f'  #{idx:3d} [{t:10s}] "{marker}"')
            print(f'        {content}...')
            print()
        
        if len(saved) > 5:
            print(f'  ... 还有 {len(saved)-5} 条')

def detect_type(content):
    """改进版：使用正则模式精确匹配"""
    import re
    
    MIN_LENGTH = 20  # 降低最小长度
    
    if len(content) < MIN_LENGTH:
        return None, None
    
    # 排除模式
    EXCLUDE_PATTERNS = [
        r"你还记得",  # "你还记得吗" 不是实践
    ]
    for p in EXCLUDE_PATTERNS:
        if re.search(p, content):
            return None, None
    
    # 解决模式 - 更宽松
    RESOLVED_PATTERNS = [
        r"(成功|完成了?|搞定).{0,10}(启动|安装|配置|运行|修复|部署|导入)",
        r"(原来|后来发现|原因是).{3,50}",
        r"(好了|可以了|修好了|改好了).{0,20}$",  # 句尾的"好了"
        r"(问题|错误|bug).{0,10}?(已|经).{0,5}(解决|修复|找到)",
        r"解决(使用?|方案)"  # "解决使用xxx"
    ]
    for p in RESOLVED_PATTERNS:
        if re.search(p, content):
            return "RESOLVED", "resolved"
    
    # 放弃模式
    GIVE_UP_PATTERNS = [
        r"放弃(使用|了|这个)?",
        r"(算了|不搞了|先这样)",
        r"换成?.{0,10}(方案|方法|试试)",
    ]
    for p in GIVE_UP_PATTERNS:
        if re.search(p, content):
            return "GIVE_UP", "give_up"
    
    # 偏好模式
    PREFERENCE_PATTERNS = [
        r"我(喜欢|习惯|偏好|更(喜欢|倾向)).{3,50}",
        r"我(一般|通常|平时).{0,5}(用|使).{3,30}",
    ]
    for p in PREFERENCE_PATTERNS:
        if re.search(p, content):
            return "PREFERENCE", "preference"
    
    # 实践模式 - 更宽松
    PRACTICE_PATTERNS = [
        r"(有个坑|踩坑)",
        r"(经验|建议|推荐).{3,50}",
        r"注意.{0,5}(要|别|不).{3,40}",
    ]
    for p in PRACTICE_PATTERNS:
        if re.search(p, content):
            return "PRACTICE", "practice"
    
    return None, None

if __name__ == "__main__":
    show_compressed_content()
