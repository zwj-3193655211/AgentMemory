#!/usr/bin/env python3
"""
AgentMemory 语义识别模型评估测试

测试流程：
1. 测试每个候选模型的启动时间
2. 测试内存占用
3. 对6个测试用例进行提取
4. 记录推理时间
5. 输出对比报告

运行方式：
  python test_model_evaluation.py
"""

import time
import json
import os
import sys
import traceback
from datetime import datetime

# 测试用例 - 来源于真实对话记录（.claude/history.jsonl）
# 这些是用户真实输入，没有经过人工修饰
REAL_DIALOG_TEST_CASES = [
    # === 错误纠正类 - 用户报告问题并解决 ===
    {
        "id": "error_fix_1",
        "name": "错误纠正-解决方法",
        "input": "找不到 server.js，请确保此脚本与 server 文件夹在同一目录。后来发现是路径问题，移到正确目录就好了",
        "expected_type": "ERROR_CORRECTION"
    },
    {
        "id": "error_fix_2", 
        "name": "错误纠正-闪退问题",
        "input": "双击bat会闪退请修复",
        "expected_type": "ERROR_CORRECTION"
    },
    {
        "id": "error_fix_3",
        "name": "错误纠正-命令问题",
        "input": "'node' 不是内部或外部命令，也不是可运行的程序或批处理文件。解决方法是配置环境变量",
        "expected_type": "ERROR_CORRECTION"
    },
    
    # === 用户偏好类 ===
    {
        "id": "user_pref_1",
        "name": "用户偏好-语言",
        "input": "中文",
        "expected_type": "USER_PROFILE"
    },
    {
        "id": "user_pref_2",
        "name": "用户偏好-搜索引擎",
        "input": "搜索引擎：优先使用必应，拒绝百度",
        "expected_type": "USER_PROFILE"
    },
    
    # === 项目上下文类 ===
    {
        "id": "project_ctx_1",
        "name": "项目上下文-技术栈",
        "input": "这个项目技术栈：Python + Django 4.x，前端Vue 3 + Element Plus，数据库MySQL 8.0",
        "expected_type": "PROJECT_CONTEXT"
    },
    {
        "id": "project_ctx_2",
        "name": "项目上下文-项目介绍",
        "input": "这是我早期的一个创意项目，我希望你实现它的联网功能和可视化界面https://github.com/xxx/HeroBattle",
        "expected_type": "PROJECT_CONTEXT"
    },
    
    # === 技能沉淀类 ===
    {
        "id": "skill_1",
        "name": "技能沉淀-docker部署",
        "input": "docker部署其实就三步：先写Dockerfile，然后docker build打个镜像，最后docker run跑起来就行",
        "expected_type": "SKILL"
    },
    {
        "id": "skill_2",
        "name": "技能沉淀-询问skill",
        "input": "你现在有哪些skill",
        "expected_type": "SKILL"
    },
    
    # === 最佳实践类 ===
    {
        "id": "best_practice_1",
        "name": "最佳实践-部署方案",
        "input": "能不能重新规划一下，把C:\\Users\\xxx\\HeroBattle中不需要的文件删掉，然后给我一个最终的部署方案",
        "expected_type": "BEST_PRACTICE"
    },
    {
        "id": "best_practice_2",
        "name": "最佳实践-打包经验",
        "input": "Windows下用PyInstaller打包有个坑，它默认用的是系统Python不是你激活的那个环境，所以要用conda run -n env_name pyinstaller这样才行",
        "expected_type": "BEST_PRACTICE"
    },
    
    # === 无效内容（应跳过）===
    {
        "id": "skip_1",
        "name": "无效-问候",
        "input": "你好",
        "expected_type": "SKIP"
    },
    {
        "id": "skip_2",
        "name": "无效-继续",
        "input": "继续",
        "expected_type": "SKIP"
    },
    {
        "id": "skip_3",
        "name": "无效-问号",
        "input": "？",
        "expected_type": "SKIP"
    },
    {
        "id": "skip_4",
        "name": "无效-确认",
        "input": "好的",
        "expected_type": "SKIP"
    },
    {
        "id": "skip_5",
        "name": "无效-单个词",
        "input": "然后你",
        "expected_type": "SKIP"
    },
    
    # === 项目经验类 ===
    {
        "id": "project_exp_1",
        "name": "项目经验-踩坑记录",
        "input": "踩坑记录：打包exe的时候遇到过几个问题，一个是conda环境的问题要用conda run指定，还有那些modelscope之类的库得加--collect-all不然会缺文件",
        "expected_type": "PROJECT_EXPERIENCE"
    },
    {
        "id": "project_exp_2",
        "name": "项目经验-成功反馈",
        "input": "运行成功了",
        "expected_type": "PROJECT_EXPERIENCE"
    }
]

# 使用真实对话测试用例
TEST_CASES = REAL_DIALOG_TEST_CASES

# 候选模型配置 - 只测试小模型（<=0.8B），跳过大模型
CANDIDATE_MODELS = [
    {
        "name": "规则匹配(基准)",
        "type": "rule",
        "priority": 1,
        "timeout": 10
    },
    # 跳过 1.5B 模型（CPU推理太慢）
    {
        "name": "Qwen2.5-0.5B",
        "type": "llm",
        "model_id": "Qwen/Qwen2.5-0.5B-Instruct",
        "priority": 2,
        "timeout": 300
    },
    {
        "name": "Qwen3-0.6B",
        "type": "llm",
        "model_id": "Qwen/Qwen3-0.6B",
        "priority": 3,
        "timeout": 300
    },
    {
        "name": "Qwen3.5-0.8B",
        "type": "llm",
        "model_id": "Qwen/Qwen3.5-0.8B",
        "priority": 4,
        "timeout": 600
    },
    {
        "name": "SmolLM2-360M",
        "type": "llm",
        "model_id": "HuggingFaceTB/SmolLM2-360M-Instruct",
        "priority": 5,
        "timeout": 300
    }
]


def get_memory_mb():
    """获取当前进程内存占用(MB)"""
    try:
        import psutil
        return psutil.Process().memory_info().rss / 1024 / 1024
    except:
        return 0


def extract_with_rules(content):
    """基于规则的语义提取 - 针对口语化输入优化"""
    import re
    
    result = {'type': 'SKIP', 'reason': '未能识别'}
    content_lower = content.lower()
    
    # 1. 项目经验/踩坑检测（优先级最高）
    if any(kw in content for kw in ['踩坑', '经验', '遇到过', '坑', '教训', '记一下', '注意点']):
        # 提取要点（口语化）
        lessons = re.findall(r'[一三四五六七八九十\d]+[是、是]([^，。；\n]{10,80})', content)
        if not lessons:
            lessons = re.findall(r'[还有，]([^，。；\n]{10,60})[的问题坑]', content)
        
        # 提取技术关键词
        tech_keywords = re.findall(r'(pyinstaller|python|conda|torch|docker|npm|git|pip)', content_lower)
        
        return {
            'type': 'PROJECT_EXPERIENCE',
            'title': content[:50] if len(content) > 50 else content,
            'tags': list(set([t for t in tech_keywords])) + ['experience'],
            'extracted': {
                'experience': content[:100],
                'lessons': [l.strip() for l in lessons[:5] if l.strip()] or [content[:100]],
                'related_technologies': list(set([t.capitalize() for t in tech_keywords]))
            }
        }
    
    # 2. 错误纠正检测（口语化关键词）
    error_keywords = ['报错', '错误', 'error', 'exception', '失败', '找不到', '缺', '崩了', '挂了']
    fix_keywords = ['解决', '修复', '后来发现', '只要', '直接', '用这个', '改成']
    
    has_error = any(kw in content_lower for kw in error_keywords)
    has_fix = any(kw in content for kw in fix_keywords)
    
    if has_error or has_fix:
        # 提取问题
        problem_match = re.search(r'(?:报错|错误|说什么)[：:，]?\s*([^。！？\n]{5,100})', content)
        if not problem_match:
            problem_match = re.search(r'([^。！？\n]{10,50})[的错误报错]', content)
        
        # 提取解决方案
        solution_match = re.search(r'(?:解决|修复|只要|直接)[了是]?([^。！？\n]{5,100})', content)
        if not solution_match:
            solution_match = re.search(r'[后来然后]([^。！？\n]{5,80})', content)
        
        return {
            'type': 'ERROR_CORRECTION',
            'title': content[:50] if len(content) > 50 else content,
            'tags': ['error', 'fix'],
            'extracted': {
                'problem': problem_match.group(1).strip()[:200] if problem_match else content[:200],
                'cause': '',
                'solution': solution_match.group(1).strip()[:500] if solution_match else ''
            }
        }
    
    # 3. 用户偏好检测（口语化）
    preference_patterns = [
        r'以后(.{1,30})吧',
        r'我喜欢(.{1,30})',
        r'我偏好(.{1,30})',
        r'我习惯(.{1,30})',
        r'用(.{1,20})吧',
        r'要(.{1,20})',
    ]
    
    for pattern in preference_patterns:
        match = re.search(pattern, content)
        if match:
            return {
                'type': 'USER_PROFILE',
                'title': f"用户偏好: {match.group(1)[:30]}",
                'tags': ['preference'],
                'extracted': {
                    'preference': match.group(0)[:200],
                    'category': 'general'
                }
            }
    
    # 4. 最佳实践检测（口语化）
    if any(kw in content for kw in ['有个坑', '建议', '推荐', '应该', '最好', '要注意', '得用']):
        return {
            'type': 'BEST_PRACTICE',
            'title': content[:50] if len(content) > 50 else content,
            'tags': ['practice'],
            'extracted': {
                'scenario': content[:100],
                'practice': content[:500]
            }
        }
    
    # 5. 项目上下文检测
    if any(kw in content for kw in ['项目', '技术栈', '用的', '框架', '加']):
        tech_keywords = re.findall(r'(react|vue|typescript|python|java|vite|webpack|docker|zustand)', content_lower)
        if tech_keywords:
            return {
                'type': 'PROJECT_CONTEXT',
                'title': "项目上下文",
                'tags': list(set([t.capitalize() for t in tech_keywords])) or ['project'],
                'extracted': {
                    'project_name': '',
                    'tech_stack': list(set([t.capitalize() for t in tech_keywords])),
                    'key_info': content[:500]
                }
            }
    
    # 6. 技能沉淀检测（口语化）
    if any(kw in content for kw in ['步骤', '流程', '就', '先', '然后', '最后']):
        # 检测是否有操作序列
        if any(w in content for w in ['步', '然后', '最后', '先']):
            return {
                'type': 'SKILL',
                'title': content[:50] if len(content) > 50 else content,
                'tags': ['skill'],
                'extracted': {
                    'skill_name': content[:50],
                    'steps': [content[:200]],
                    'prerequisites': []
                }
            }
    
    return result


def load_llm(model_id, timeout=300):
    """加载LLM模型"""
    import torch
    from transformers import AutoModelForCausalLM, AutoTokenizer
    
    print(f"  正在加载 {model_id}...")
    
    cache_dir = os.path.expanduser("~/.agentmemory/models")
    os.makedirs(cache_dir, exist_ok=True)
    
    tokenizer = AutoTokenizer.from_pretrained(
        model_id,
        cache_dir=cache_dir,
        trust_remote_code=True
    )
    
    model = AutoModelForCausalLM.from_pretrained(
        model_id,
        cache_dir=cache_dir,
        torch_dtype=torch.float32,
        trust_remote_code=True
    )
    model.eval()
    
    # 检测是否是Qwen3系列（需要关闭思考模式）
    # 区分 Qwen3 和 Qwen3.5
    # Qwen3 需要显式关闭思考模式
    # Qwen3.5 小模型 (<=14B) 默认非思考模式，不需要设置
    is_qwen3_only = ("Qwen3" in model_id or "qwen3" in model_id.lower()) and "Qwen3.5" not in model_id and "qwen3.5" not in model_id.lower()
    
    return model, tokenizer, is_qwen3_only


def extract_with_llm(content, model, tokenizer, is_qwen3_only=False):
    """使用LLM进行语义提取
    
    Args:
        content: 输入内容
        model: 模型
        tokenizer: 分词器
        is_qwen3_only: 是否是Qwen3系列模型（Qwen3.5除外，需要关闭思考模式）
    """
    import torch
    import re
    
    # 改进的提示词 - 明确输出格式要求
    system_prompt = """你是一个对话内容分析助手。分析用户输入，判断属于以下哪种类型：

类型定义：
- ERROR_CORRECTION: 错误/问题及其解决方案
- USER_PROFILE: 用户偏好、习惯、要求
- BEST_PRACTICE: 建议、推荐做法、最佳实践
- PROJECT_CONTEXT: 项目信息、技术栈、架构
- SKILL: 操作步骤、流程、方法
- PROJECT_EXPERIENCE: 踩坑经验、总结、教训
- SKIP: 无实质内容的简单确认（如"好的"、"明白了"）

输出要求：
1. 必须输出合法JSON，不要有其他文字
2. 格式：{"type":"类型名","title":"简短标题","tags":["标签"],"extracted":{...}}
3. 无实质内容返回：{"type":"SKIP","reason":"原因"}

/no_think"""

    user_message = f"""分析以下内容并输出JSON：

"{content}"

输出JSON："""

    # 构建消息
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_message}
    ]
    
    # 应用chat模板
    if is_qwen3_only:
        # 只有 Qwen3 (非 Qwen3.5) 需要显式关闭思考模式
        # Qwen3.5 小模型默认就是非思考模式
        text = tokenizer.apply_chat_template(
            messages,
            tokenize=False,
            add_generation_prompt=True,
            enable_thinking=False  # 关键：关闭思考模式
        )
        print(f"  [Qwen3系列] 已关闭思考模式")
    else:
        text = tokenizer.apply_chat_template(
            messages,
            tokenize=False,
            add_generation_prompt=True
        )
    
    inputs = tokenizer([text], return_tensors="pt")
    
    with torch.no_grad():
        # 非思考模式推荐参数
        outputs = model.generate(
            **inputs,
            max_new_tokens=256,
            temperature=0.7,
            top_p=0.8,
            top_k=20,
            do_sample=True,
            pad_token_id=tokenizer.eos_token_id
        )
    
    # 解码输出
    output_ids = outputs[0][inputs['input_ids'].shape[1]:].tolist()
    reply = tokenizer.decode(output_ids, skip_special_tokens=True)
    
    # 清理可能的思考块残留
    if "" in reply and "" in reply:
        # 移除思考块
        reply = re.sub(r'<think>.*?</think>', '', reply, flags=re.DOTALL)
    
    # 解析 JSON
    try:
        # 尝试多种方式提取JSON
        reply = reply.strip()
        
        # 方式1: 直接解析
        if reply.startswith('{'):
            json_str = reply
        # 方式2: 提取代码块中的JSON
        elif '```json' in reply:
            json_str = reply.split('```json')[1].split('```')[0].strip()
        elif '```' in reply:
            json_str = reply.split('```')[1].split('```')[0].strip()
        # 方式3: 正则匹配
        else:
            json_match = re.search(r'\{[\s\S]*\}', reply)
            json_str = json_match.group() if json_match else '{}'
        
        result = json.loads(json_str.strip())
        
        # 验证必要字段
        if 'type' not in result:
            result = {'type': 'SKIP', 'reason': '缺少type字段'}
        
        return result
        
    except json.JSONDecodeError as e:
        return {'type': 'SKIP', 'reason': f'JSON解析失败: {str(e)[:50]}', 'raw': reply[:200]}


def test_model(model_config, test_cases):
    """测试单个模型"""
    result = {
        "name": model_config["name"],
        "type": model_config["type"],
        "startup_time_s": 0,
        "memory_mb": 0,
        "test_results": [],
        "success": False,
        "error": None
    }
    
    try:
        # 记录初始内存
        initial_memory = get_memory_mb()
        
        # 加载模型
        print(f"\n{'='*50}")
        print(f"测试模型: {model_config['name']}")
        print(f"{'='*50}")
        
        start = time.time()
        
        if model_config["type"] == "rule":
            # 规则模式无需加载
            model, tokenizer, is_qwen3_only = None, None, False
        else:
            # 加载LLM
            model, tokenizer, is_qwen3_only = load_llm(model_config["model_id"])
        
        result["startup_time_s"] = round(time.time() - start, 2)
        result["memory_mb"] = round(get_memory_mb() - initial_memory, 2)
        
        print(f"  加载完成: {result['startup_time_s']}秒, 内存: {result['memory_mb']}MB")
        
        # 测试各用例
        for case in test_cases:
            print(f"\n  测试: {case['name']}")
            
            case_start = time.time()
            
            if model_config["type"] == "rule":
                extracted = extract_with_rules(case["input"])
            else:
                extracted = extract_with_llm(case["input"], model, tokenizer, is_qwen3_only)
            
            inference_time = round((time.time() - case_start) * 1000, 2)
            
            # 检查类型是否正确
            type_correct = extracted.get("type") == case["expected_type"]
            
            case_result = {
                "case_id": case["id"],
                "inference_time_ms": inference_time,
                "type_correct": type_correct,
                "extracted_type": extracted.get("type"),
                "expected_type": case["expected_type"],
                "result": extracted
            }
            
            result["test_results"].append(case_result)
            
            status = "OK" if type_correct else "X"
            print(f"    [{status}] 类型: {extracted.get('type')} (期望: {case['expected_type']})")
            print(f"    耗时: {inference_time}ms")
        
        result["success"] = True
        
        # 清理模型释放内存
        if model is not None:
            del model
            del tokenizer
            import gc
            gc.collect()
        
    except Exception as e:
        result["error"] = str(e)
        print(f"  [ERROR] {e}")
        traceback.print_exc()
    
    return result


def print_summary(results):
    """打印测试摘要"""
    print("\n" + "="*80)
    print("评估结果摘要")
    print("="*80)
    
    print(f"\n{'模型':<20} {'启动时间':<12} {'内存占用':<12} {'类型正确率':<12} {'平均推理':<12}")
    print("-"*70)
    
    for r in results:
        if r["success"]:
            correct = sum(1 for c in r["test_results"] if c["type_correct"])
            total = len(r["test_results"])
            correct_rate = f"{correct}/{total}"
            avg_time = sum(c["inference_time_ms"] for c in r["test_results"]) / total
            avg_time_str = f"{avg_time:.1f}ms"
        else:
            correct_rate = "失败"
            avg_time_str = "-"
        
        print(f"{r['name']:<20} {r['startup_time_s']}s{'':<8} {r['memory_mb']}MB{'':<8} {correct_rate:<12} {avg_time_str:<12}")
    
    print("\n" + "="*80)


def main():
    print("="*80)
    print("AgentMemory 语义识别模型评估")
    print(f"测试时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("="*80)
    
    # 设置镜像
    os.environ['HF_ENDPOINT'] = 'https://hf-mirror.com'
    
    results = []
    
    for model_config in CANDIDATE_MODELS:
        result = test_model(model_config, TEST_CASES)
        results.append(result)
        
        # 每个模型测试后暂停，避免内存叠加
        print(f"\n模型 {model_config['name']} 测试完成")
        time.sleep(2)
    
    # 打印摘要
    print_summary(results)
    
    # 保存结果
    report_path = os.path.join(os.path.dirname(__file__), "evaluation_report.json")
    with open(report_path, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    
    print(f"\n详细报告已保存: {report_path}")


if __name__ == "__main__":
    main()
