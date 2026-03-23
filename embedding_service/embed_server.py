#!/usr/bin/env python3
"""
AgentMemory 嵌入服务

支持三种模式：
- 轻量模式 (默认): 仅 bge-small-zh-v1.5，秒启动，规则提取
- 本地LLM模式: bge-small + 本地LLM（transformers）
- API模式: bge-small + 外部API（OpenAI兼容接口）

配置方式（环境变量）：
  LLM_MODE=disabled          # 轻量模式（默认）
  LLM_MODE=local             # 本地LLM模式
  LLM_MODE=api               # API模式
  
  # API模式配置
  LLM_API_PROVIDER=openai    # openai, zhipu, deepseek, ollama, custom
  LLM_API_BASE=https://api.openai.com/v1
  LLM_API_KEY=sk-xxx
  LLM_API_MODEL=gpt-4o-mini
  
  # 本地模式配置
  LLM_LOCAL_MODEL=Qwen/Qwen3-0.6B

启动方式：
  python embed_server.py                              # 轻量模式
  LLM_MODE=api LLM_API_KEY=sk-xxx python embed_server.py  # API模式
  LLM_MODE=local python embed_server.py              # 本地LLM模式
"""

from flask import Flask, request, jsonify
from flask_cors import CORS
import numpy as np
import logging
import os
import json
import re
import requests
import threading

# 配置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)
CORS(app)

# ========== 配置 ==========
# 不再全局设置离线模式，改为动态检测
# 用户首次选择模型时会自动下载，已下载的模型使用离线模式

# 国内镜像（下载时使用）
HF_MIRROR = 'https://hf-mirror.com'

MODEL_CACHE = os.path.expanduser("~/.agentmemory/models")
os.makedirs(MODEL_CACHE, exist_ok=True)

# LLM 模式: disabled, local, api
LLM_MODE = os.environ.get('LLM_MODE', 'disabled').lower()

# API 配置
LLM_API_PROVIDER = os.environ.get('LLM_API_PROVIDER', 'openai')
LLM_API_BASE = os.environ.get('LLM_API_BASE', 'https://api.openai.com/v1')
LLM_API_KEY = os.environ.get('LLM_API_KEY', '')
LLM_API_MODEL = os.environ.get('LLM_API_MODEL', 'gpt-4o-mini')
LLM_API_TIMEOUT = int(os.environ.get('LLM_API_TIMEOUT', '30'))

# 本地模型配置
LLM_LOCAL_MODEL = os.environ.get('LLM_LOCAL_MODEL', 'Qwen/Qwen3-0.6B')

# Embedding 模型配置
EMBEDDING_MODEL = os.environ.get('EMBEDDING_MODEL', 'BAAI/bge-small-zh-v1.5')

# 支持的 Embedding 模型列表
EMBEDDING_MODELS = {
    'BAAI/bge-small-zh-v1.5': {
        'name': 'BGE-small-zh-v1.5',
        'dimension': 512,
        'size': '~100MB',
        'description': '轻量级中文模型，速度快',
        'download_size_mb': 100
    },
    'Qwen/Qwen3-Embedding-0.6B': {
        'name': 'Qwen3-Embedding-0.6B',
        'dimension': 1024,
        'size': '~1.2GB',
        'description': '高性能模型，支持32K上下文，中文效果更好',
        'download_size_mb': 1200
    }
}

# 模型下载状态缓存
_model_download_status = {}  # {model_id: 'downloading' | 'ready' | 'error' | 'not_downloaded'}

# 模型下载进度缓存
_model_download_progress = {}  # {model_id: {'downloaded_mb': float, 'total_mb': float, 'percent': int, 'speed_mbps': float, 'current_file': str, 'files_done': int, 'files_total': int, 'error': str, 'start_time': float}}

# 配置文件路径
CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'config.json')

# ========== 配置持久化 ==========
def save_config():
    """保存配置到文件"""
    config = {
        'llm_mode': LLM_MODE,
        'llm_api_provider': LLM_API_PROVIDER,
        'llm_api_base': LLM_API_BASE,
        'llm_api_key': LLM_API_KEY,
        'llm_api_model': LLM_API_MODEL,
        'llm_local_model': LLM_LOCAL_MODEL,
        'embedding_model': EMBEDDING_MODEL
    }
    try:
        with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
            json.dump(config, f, indent=2, ensure_ascii=False)
        logger.info(f"配置已保存到: {CONFIG_FILE}")
    except Exception as e:
        logger.error(f"保存配置失败: {e}")

def load_config():
    """从文件加载配置"""
    global LLM_MODE, LLM_API_PROVIDER, LLM_API_BASE, LLM_API_KEY, LLM_API_MODEL, LLM_LOCAL_MODEL, EMBEDDING_MODEL
    
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                config = json.load(f)
            
            # 应用配置（环境变量优先级更高）
            if 'llm_mode' in config and not os.environ.get('LLM_MODE'):
                LLM_MODE = config['llm_mode']
            if 'llm_api_provider' in config and not os.environ.get('LLM_API_PROVIDER'):
                LLM_API_PROVIDER = config['llm_api_provider']
            if 'llm_api_base' in config and not os.environ.get('LLM_API_BASE'):
                LLM_API_BASE = config['llm_api_base']
            if 'llm_api_key' in config and not os.environ.get('LLM_API_KEY'):
                LLM_API_KEY = config['llm_api_key']
            if 'llm_api_model' in config and not os.environ.get('LLM_API_MODEL'):
                LLM_API_MODEL = config['llm_api_model']
            if 'llm_local_model' in config and not os.environ.get('LLM_LOCAL_MODEL'):
                LLM_LOCAL_MODEL = config['llm_local_model']
            if 'embedding_model' in config and not os.environ.get('EMBEDDING_MODEL'):
                EMBEDDING_MODEL = config['embedding_model']
            
            logger.info(f"配置已从文件加载: {CONFIG_FILE}")
        except Exception as e:
            logger.error(f"加载配置失败: {e}")

# 启动时加载配置
load_config()

# 提取提示词
EXTRACTION_PROMPT = """分析以下对话内容，提取结构化记忆信息。

对话内容：
{content}

请判断属于哪种类型并提取信息：
1. ERROR_CORRECTION（错误纠正）: problem, cause, solution
2. USER_PROFILE（用户偏好）: preference, category  
3. BEST_PRACTICE（最佳实践）: scenario, practice
4. PROJECT_CONTEXT（项目上下文）: project_name, tech_stack[], key_info
5. SKILL（技能沉淀）: skill_name, steps[], prerequisites

无价值信息返回: {"type": "SKIP", "reason": "原因"}

严格按JSON格式返回：
{"type": "类型", "title": "简短标题", "tags": ["标签"], "extracted": {...}}"""

# 模型缓存
embedding_model = None
llm_model = None
llm_tokenizer = None
is_qwen3_only = False  # 是否是 Qwen3（需要关闭思考模式）
_model_lock = threading.Lock()  # 防止并发加载模型
_current_embedding_model_id = None  # 当前加载的 embedding 模型 ID


def check_model_exists(model_id: str) -> bool:
    """检查模型是否已下载到本地缓存（包括模型权重文件）"""
    from pathlib import Path
    
    # 确保路径正确展开
    cache_dir = Path(MODEL_CACHE).expanduser().resolve()
    
    # HuggingFace 缓存结构: models--org--model_name/
    model_folder = model_id.replace('/', '--')
    cache_path = cache_dir / f"models--{model_folder}"
    
    logger.debug(f"检查模型路径: {cache_path}")
    
    if not cache_path.exists():
        logger.debug(f"模型目录不存在: {cache_path}")
        return False
    
    # 检查是否有快照（完整的模型文件）
    snapshots_path = cache_path / "snapshots"
    if not snapshots_path.exists():
        logger.debug(f"快照目录不存在: {snapshots_path}")
        return False
    
    snapshots = list(snapshots_path.iterdir())
    if not snapshots:
        logger.debug(f"没有找到快照")
        return False
    
    # 检查是否有必要的模型文件（配置文件 + 模型权重）
    for snapshot in snapshots:
        config_path = snapshot / "config.json"
        st_config_path = snapshot / "config_sentence_transformers.json"
        
        # 必须有配置文件
        if not (config_path.exists() or st_config_path.exists()):
            continue
        
        # 检查模型权重文件是否存在（.safetensors 或 .bin）
        model_files = list(snapshot.glob("*.safetensors")) + list(snapshot.glob("pytorch_model*.bin"))
        # 过滤掉不完整的文件（.incomplete 在 blobs 目录，但这里检查 snapshot）
        valid_model_files = [f for f in model_files if f.stat().st_size > 0]
        
        if valid_model_files:
            logger.info(f"模型已存在: {model_id}, 快照: {snapshot.name}, 权重文件: {[f.name for f in valid_model_files]}")
            return True
        else:
            logger.debug(f"快照 {snapshot.name} 有配置文件但缺少模型权重")
    
    logger.debug(f"快照中没有找到完整的模型文件")
    return False


def get_embedding_model(model_id=None):
    """加载 embedding 模型，支持动态切换和按需下载"""
    global embedding_model, _current_embedding_model_id
    
    if model_id is None:
        model_id = EMBEDDING_MODEL
    
    # 如果模型已加载且是同一个模型，直接返回
    if embedding_model is not None and _current_embedding_model_id == model_id:
        return embedding_model, EMBEDDING_MODELS.get(model_id, {}).get('dimension', 512)
    
    with _model_lock:
        if embedding_model is None or _current_embedding_model_id != model_id:
            from sentence_transformers import SentenceTransformer
            
            # 检查模型是否已下载
            model_exists = check_model_exists(model_id)
            
            # 根据模型是否存在设置离线/在线模式
            if model_exists:
                logger.info(f"模型 {model_id} 已存在，使用离线模式加载")
                os.environ['HF_HUB_OFFLINE'] = '1'
                os.environ['TRANSFORMERS_OFFLINE'] = '1'
            else:
                logger.info(f"模型 {model_id} 未下载，将从网络下载")
                os.environ.pop('HF_HUB_OFFLINE', None)
                os.environ.pop('TRANSFORMERS_OFFLINE', None)
                # 使用国内镜像加速
                os.environ['HF_ENDPOINT'] = HF_MIRROR
            
            # 如果切换模型，先释放旧模型
            if embedding_model is not None:
                del embedding_model
                embedding_model = None
            
            try:
                logger.info(f"加载 embedding 模型 ({model_id})...")
                embedding_model = SentenceTransformer(
                    model_id,
                    cache_folder=MODEL_CACHE
                )
                _current_embedding_model_id = model_id
                dimension = EMBEDDING_MODELS.get(model_id, {}).get('dimension', 512)
                logger.info(f"Embedding 模型加载完成，维度: {dimension}")
                
                # 更新下载状态
                _model_download_status[model_id] = 'ready'
                
                return embedding_model, dimension
            except Exception as e:
                _model_download_status[model_id] = 'error'
                logger.error(f"加载模型失败: {e}")
                raise
    
    return embedding_model, EMBEDDING_MODELS.get(model_id, {}).get('dimension', 512)


def get_local_llm():
    """加载本地 LLM 模型"""
    global llm_model, llm_tokenizer, is_qwen3_only
    if llm_model is None:
        with _model_lock:
            if llm_model is None:  # double check
                logger.info(f"加载本地 LLM 模型 ({LLM_LOCAL_MODEL})...")
                import torch
                from transformers import AutoModelForCausalLM, AutoTokenizer
                
                # 检测是否是 Qwen3 系列（需要关闭思考模式）
                # Qwen3（非 Qwen3.5）需要显式关闭思考模式
                is_qwen3_only = ("Qwen3" in LLM_LOCAL_MODEL or "qwen3" in LLM_LOCAL_MODEL.lower()) \
                                and "Qwen3.5" not in LLM_LOCAL_MODEL and "qwen3.5" not in LLM_LOCAL_MODEL.lower()
                
                llm_tokenizer = AutoTokenizer.from_pretrained(
                    LLM_LOCAL_MODEL,
                    cache_dir=MODEL_CACHE,
                    trust_remote_code=True
                )
                llm_model = AutoModelForCausalLM.from_pretrained(
                    LLM_LOCAL_MODEL,
                    cache_dir=MODEL_CACHE,
                    torch_dtype=torch.float32,
                    trust_remote_code=True
                )
                llm_model.eval()
                
                if is_qwen3_only:
                    logger.info(f"  [Qwen3系列] 检测到，需关闭思考模式")
                logger.info("本地 LLM 模型加载完成")
    return llm_model, llm_tokenizer


def call_api_llm(content: str) -> dict:
    """调用外部 API 进行语义提取"""
    if not LLM_API_KEY:
        raise ValueError("LLM_API_KEY 未配置")
    
    prompt = EXTRACTION_PROMPT.format(content=content[:2000])
    
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {LLM_API_KEY}"
    }
    
    payload = {
        "model": LLM_API_MODEL,
        "messages": [
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.1,
        "max_tokens": 500
    }
    
    try:
        resp = requests.post(
            f"{LLM_API_BASE}/chat/completions",
            headers=headers,
            json=payload,
            timeout=LLM_API_TIMEOUT
        )
        resp.raise_for_status()
        
        result = resp.json()
        reply = result["choices"][0]["message"]["content"]
        
        # 解析 JSON
        return parse_llm_response(reply)
        
    except requests.exceptions.Timeout:
        logger.error("API 请求超时")
        return {'type': 'SKIP', 'reason': 'API超时'}
    except requests.exceptions.RequestException as e:
        logger.error(f"API 请求失败: {e}")
        return {'type': 'SKIP', 'reason': f'API错误: {str(e)}'}


def get_embedding(text):
    """生成文本向量"""
    model = get_embedding_model()
    embedding = model.encode(text, normalize_embeddings=True)
    return embedding


def extract_with_rules(content):
    """基于规则的语义提取 (轻量模式)
    
    支持类型：ERROR_CORRECTION, USER_PROFILE, BEST_PRACTICE, 
              PROJECT_CONTEXT, SKILL, PROJECT_EXPERIENCE, SKIP
    
    优化：根据评估报告中的误分类案例修复规则
    """
    result = {'type': 'SKIP', 'reason': '未能识别'}
    
    content_lower = content.lower()
    
    # ===== 0. 跳过无效内容 =====
    # 太短的内容不识别
    if len(content.strip()) < 10:
        return {'type': 'SKIP', 'reason': '内容太短'}
    
    # 纯问候语/客套话不识别
    skip_phrases = ['好的', '明白了', '了解', '好的明白了', '没问题', '可以', '嗯', '好', '收到']
    if content.strip() in skip_phrases:
        return {'type': 'SKIP', 'reason': '无实质内容'}
    
    # 1. 用户偏好检测 (优先级高)
    # 修复：增加更多用户偏好关键词，避免被误识别为 SKILL/SKIP
    user_pref_keywords = [
        '我喜欢', '我偏好', '我习惯', '我想要', '我不用', '我不要',
        '请记住', '记住', '以后', '下次', '希望', '想要',
        '优先使用', '拒绝', '不要用', '少用', '常用',
        '我喜欢用', '我通常用', '我一般用', '我倾向',
        '搜索引擎', '搜索', '必应', '百度', '谷歌'
    ]
    
    if any(kw in content for kw in user_pref_keywords):
        # 提取偏好内容
        pref_match = re.search(r'(?:我|请|以后|下次).{0,50}', content)
        title = pref_match.group(0).strip()[:50] if pref_match else "用户偏好"
        
        return {
            'type': 'USER_PROFILE',
            'title': title,
            'tags': ['preference'],
            'extracted': {
                'preference': content[:200],
                'category': 'general'
            }
        }
    
    # 2. 技能沉淀检测 (优先于最佳实践)
    # 修复：增加更多技能关键词，避免被误识别为 SKIP
    skill_keywords = [
        '擅长', '专长', '精通', '熟悉', '掌握',
        '步骤', '流程', '第1步', '第2步', '第一步', '第二步', '第三步',
        '如何', '怎么', '方法', '教程', '流程是',
        '先', '再', '然后', '最后', '接着',
        '首先', '其次', '最后', '总之',
        '三步', '几步', '几步走'
    ]
    
    if any(kw in content for kw in skill_keywords):
        # 提取步骤
        steps = re.findall(r'(第[一二三四五六七八九十0-9]+步[：:]?.{10,80})', content)
        if not steps:
            steps = re.findall(r'(\d+[\.、].{10,80})', content)
        
        # 提取标题
        title_match = re.search(r'([^\n。？！]{10,40})', content)
        title = title_match.group(1).strip() if title_match else "技能/步骤"
        
        return {
            'type': 'SKILL',
            'title': title[:50],
            'tags': ['skill', 'workflow'],
            'extracted': {
                'skill_name': title[:50],
                'steps': steps[:5] if steps else [content[:100]],
                'prerequisites': []
            }
        }
    
    # 3. 最佳实践检测
    # 修复：调整顺序，在技能之后，减少误识别
    practice_keywords = ['最佳实践', '建议', '推荐', '应该', '最好', '最好用', '建议用']
    
    if any(kw in content for kw in practice_keywords):
        # 提取标题
        title_match = re.search(r'([^\n。？！]{10,50})', content)
        title = title_match.group(1).strip() if title_match else "最佳实践"
        
        return {
            'type': 'BEST_PRACTICE',
            'title': title[:60],
            'tags': ['practice', 'recommendation'],
            'extracted': {
                'scenario': content[:100],
                'practice': content[:500]
            }
        }
    
    # 4. 项目上下文检测
    # 修复：增加更多项目上下文关键词，避免被误识别为 SKIP
    project_ctx_keywords = [
        '项目', '工程', 'project', '技术栈', '框架',
        '用的', '使用的是', '基于', '开发', '源码',
        'github', '仓库', '代码', '目录', '结构'
    ]
    
    if any(kw in content for kw in project_ctx_keywords):
        tech_keywords = re.findall(
            r'(python|java|javascript|typescript|react|vue|angular|spring|django|flask|node|typescript|go|rust|php|MySQL|PostgreSQL|MongoDB|Redis|docker|kubernetes)',
            content_lower
        )
        tech_keywords = list(set(tech_keywords))
        
        # 提取标题
        title_match = re.search(r'([^\n。？！]{10,40})', content)
        title = title_match.group(1).strip() if title_match else "项目上下文"
        
        return {
            'type': 'PROJECT_CONTEXT',
            'title': title[:50],
            'tags': tech_keywords or ['project'],
            'extracted': {
                'project_name': '',
                'tech_stack': tech_keywords,
                'key_info': content[:500]
            }
        }
    
    # 5. 错误纠正检测
    # 包含错误关键词且有解决方案
    error_keywords = ['错误', '报错', '失败', 'exception', 'error', 'bug', '问题', '修复', '解决', '不行']
    resolved_markers = ['解决了', '修复了', '后来', '原来是', '原因', '所以', '改用', '改成', '改成', '即可', '才行']
    
    if any(kw in content for kw in error_keywords):
        # 必须有解决/修复/原因等标记才识别为错误纠正
        if any(kw in content for kw in ['解决', '修复', '后来', '原因', '改成', '改为', '改成', '即可', '才行']):
            title_match = re.search(r'([^\n。？！]{10,40})', content)
            title = title_match.group(1).strip() if title_match else "错误处理"
            
            return {
                'type': 'ERROR_CORRECTION',
                'title': title[:60],
                'tags': ['error', 'fix'],
                'extracted': {
                    'problem': content[:200],
                    'cause': '',
                    'solution': content[:500]
                }
            }
    
    # 6. 项目经验检测 (优先级最低)
    experience_keywords = ['经验', '总结', '踩坑', '坑点', '教训', '记录']
    
    if any(kw in content for kw in experience_keywords):
        # 提取经验条目
        lessons = []
        
        # 匹配带编号的条目 (如 "1) xxx", "1. xxx", "1、xxx")
        numbered_items = re.findall(r'[\d]+[）\.\、]\s*(.+?)(?=[\d]+[）\.\、]|$)', content, re.DOTALL)
        if numbered_items:
            lessons = [item.strip()[:100] for item in numbered_items if item.strip()]
        
        # 匹配带括号的条目 (如 "1) xxx")
        if not lessons:
            paren_items = re.findall(r'\d+\)\s*(.+?)(?=\d+\)|$)', content, re.DOTALL)
            if paren_items:
                lessons = [item.strip()[:100] for item in paren_items if item.strip()]
        
        # 提取相关技术
        tech_keywords = re.findall(r'(pyinstaller|docker|conda|python|pip|torch|numpy|react|vue|spring)', content_lower)
        
        if lessons or '经验' in content:
            title_match = re.search(r'([^\n。]{10,50}经验)', content)
            title = title_match.group(1).strip() if title_match else "项目经验总结"
            
            return {
                'type': 'PROJECT_EXPERIENCE',
                'title': title[:100],
                'tags': list(set(tech_keywords)) + ['experience'],
                'extracted': {
                    'experience': title,
                    'lessons': lessons[:5] if lessons else [content[:200]],
                    'related_technologies': list(set(tech_keywords))
                }
            }
    
    # 2. 错误纠正检测
    error_patterns = [
        r'错误[：:]\s*(.+?)(?=\n|原因|$)',
        r'报错[：:]\s*(.+?)(?=\n|$)',
        r'exception[：:]\s*(.+?)(?=\n|$)',
        r'error[：:]\s*(.+?)(?=\n|$)',
        r'failed[：:]\s*(.+?)(?=\n|$)',
        r'失败[：:]\s*(.+?)(?=\n|$)',
    ]
    fix_patterns = [
        r'解决[方法方案]*[：:]\s*(.+?)(?=\n|$)',
        r'修复[：:]\s*(.+?)(?=\n|$)',
        r'改为[：:]\s*(.+?)(?=\n|$)',
        r'修改为[：:]\s*(.+?)(?=\n|$)',
        r'fix[：:]\s*(.+?)(?=\n|$)',
    ]
    
    problem = None
    solution = None
    
    for pattern in error_patterns:
        match = re.search(pattern, content, re.IGNORECASE)
        if match:
            problem = match.group(1).strip()[:200]
            break
    
    for pattern in fix_patterns:
        match = re.search(pattern, content, re.IGNORECASE)
        if match:
            solution = match.group(1).strip()[:500]
            break
    
    if problem or '错误' in content or 'error' in content_lower or 'exception' in content_lower:
        # 提取标题
        title_match = re.search(r'([^\n。！？]{10,50})', content)
        title = title_match.group(1).strip() if title_match else "错误处理"
        
        return {
            'type': 'ERROR_CORRECTION',
            'title': title[:100],
            'tags': ['error', 'fix'],
            'extracted': {
                'problem': problem or content[:200],
                'cause': '',
                'solution': solution or ''
            }
        }
    
    # 3. 用户偏好检测
    preference_patterns = [
        r'我喜欢(.{1,50})',
        r'我偏好(.{1,50})',
        r'我习惯(.{1,50})',
        r'我想要(.{1,50})',
        r'请记住(.{1,30})',
        r'请(.{1,30})',
        r'不要(.{1,30})',
        r'希望(.{1,30})',
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
    
    # 4. 最佳实践检测
    if any(kw in content for kw in ['最佳实践', '建议', '推荐', '应该', '最好']):
        title_match = re.search(r'([^\n。]{10,50})', content)
        return {
            'type': 'BEST_PRACTICE',
            'title': title_match.group(1).strip() if title_match else "最佳实践",
            'tags': ['practice', 'recommendation'],
            'extracted': {
                'scenario': content[:100],
                'practice': content[:500]
            }
        }
    
    # 5. 项目上下文检测
    if any(kw in content for kw in ['项目', '工程', 'project', '技术栈', '框架']):
        tech_keywords = re.findall(r'(python|java|javascript|react|vue|spring|django|flask|node|typescript|go|rust)', content_lower)
        return {
            'type': 'PROJECT_CONTEXT',
            'title': "项目上下文",
            'tags': list(set(tech_keywords)) or ['project'],
            'extracted': {
                'project_name': '',
                'tech_stack': list(set(tech_keywords)),
                'key_info': content[:500]
            }
        }
    
    # 6. 技能沉淀检测
    if any(kw in content for kw in ['步骤', '流程', '第一步', '如何', '怎么', '方法']):
        steps = re.findall(r'(第[一二三四五六七八九十\d]+步[：:]?.{10,100})', content)
        if not steps:
            steps = re.findall(r'(\d+[\.、].{10,100})', content)
        
        if steps:
            return {
                'type': 'SKILL',
                'title': content[:50],
                'tags': ['skill', 'workflow'],
                'extracted': {
                    'skill_name': content[:50],
                    'steps': steps[:5],
                    'prerequisites': []
                }
            }
    
    return result


def parse_llm_response(reply: str) -> dict:
    """解析 LLM 返回的 JSON"""
    # 清理 markdown 代码块
    if '```json' in reply:
        reply = reply.split('```json')[1].split('```')[0]
    elif '```' in reply:
        reply = reply.split('```')[1].split('```')[0]
    
    # 提取 JSON
    json_match = re.search(r'\{[\s\S]*\}', reply)
    if json_match:
        reply = json_match.group()
    
    try:
        return json.loads(reply.strip())
    except json.JSONDecodeError:
        return {'type': 'SKIP', 'reason': 'JSON解析失败'}


def extract_with_local_llm(content: str) -> dict:
    """使用本地 LLM 进行语义提取"""
    import torch
    
    model, tokenizer = get_local_llm()
    if model is None:
        return {'type': 'SKIP', 'reason': '本地LLM未加载'}
    
    # 构建消息格式
    system_prompt = "你是一个专业的对话分析助手，擅长从对话中提取结构化信息。"
    user_message = EXTRACTION_PROMPT.format(content=content[:1500])
    
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_message}
    ]
    
    # 应用chat模板，Qwen3需要关闭思考模式
    if is_qwen3_only:
        text = tokenizer.apply_chat_template(
            messages,
            tokenize=False,
            add_generation_prompt=True,
            enable_thinking=False  # 关键：关闭思考模式
        )
        logger.debug("[Qwen3系列] 思考模式已关闭")
    else:
        text = tokenizer.apply_chat_template(
            messages,
            tokenize=False,
            add_generation_prompt=True
        )
    
    inputs = tokenizer([text], return_tensors="pt")
    
    with torch.no_grad():
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
    
    return parse_llm_response(reply)


@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    # 注意：不在 health 检查中预加载模型，避免阻塞
    
    # 构建 LLM 信息
    llm_info = None
    if LLM_MODE == 'local':
        llm_info = {
            'mode': 'local',
            'model': LLM_LOCAL_MODEL
        }
    elif LLM_MODE == 'api':
        llm_info = {
            'mode': 'api',
            'provider': LLM_API_PROVIDER,
            'model': LLM_API_MODEL,
            'base': LLM_API_BASE,
            'configured': bool(LLM_API_KEY)
        }
    else:
        llm_info = {
            'mode': 'disabled'
        }
    
    # 获取当前 embedding 模型信息
    emb_info = EMBEDDING_MODELS.get(EMBEDDING_MODEL, {})
    
    # 检查模型是否已下载（不加载）
    model_downloaded = check_model_exists(EMBEDDING_MODEL)
    
    return jsonify({
        'status': 'ok',
        'embedding_model': EMBEDDING_MODEL,
        'embedding_model_name': emb_info.get('name', EMBEDDING_MODEL),
        'dimension': emb_info.get('dimension', 512),
        'model_downloaded': model_downloaded,
        'llm': llm_info
    })


@app.route('/embed', methods=['POST'])
def embed():
    """生成文本向量"""
    try:
        data = request.get_json()
        texts = data.get('texts', [])
        
        if not texts:
            return jsonify({'error': 'texts 不能为空'}), 400
        
        if not isinstance(texts, list):
            return jsonify({'error': 'texts 必须是数组'}), 400
        
        model, dimension = get_embedding_model()
        embeddings = model.encode(texts, normalize_embeddings=True)
        
        return jsonify({
            'embeddings': [e.tolist() for e in embeddings],
            'dimension': dimension,
            'count': len(texts),
            'model': _current_embedding_model_id
        })
        
    except Exception as e:
        logger.error(f"生成向量失败: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/similarity', methods=['POST'])
def similarity():
    """计算两个文本的相似度"""
    try:
        data = request.get_json()
        text1 = data.get('text1', '')
        text2 = data.get('text2', '')
        
        if not text1 or not text2:
            return jsonify({'error': 'text1 和 text2 不能为空'}), 400
        
        model, _ = get_embedding_model()
        embeddings = model.encode([text1, text2], normalize_embeddings=True)
        sim = float(np.dot(embeddings[0], embeddings[1]))
        
        return jsonify({'similarity': sim})
        
    except Exception as e:
        logger.error(f"计算相似度失败: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/batch_similarity', methods=['POST'])
def batch_similarity():
    """批量计算相似度"""
    try:
        data = request.get_json()
        query = data.get('query', '')
        candidates = data.get('candidates', [])
        
        if not query or not candidates:
            return jsonify({'error': 'query 和 candidates 不能为空'}), 400
        
        model = get_embedding_model()
        all_texts = [query] + candidates
        embeddings = model.encode(all_texts, normalize_embeddings=True)
        
        query_emb = embeddings[0]
        similarities = [float(np.dot(query_emb, e)) for e in embeddings[1:]]
        
        return jsonify({'similarities': similarities})
        
    except Exception as e:
        logger.error(f"批量计算相似度失败: {e}")
        return jsonify({'error': str(e)}), 500


@app.route('/extract', methods=['POST'])
def extract():
    """语义提取"""
    try:
        data = request.get_json()
        content = data.get('content', '')
        
        if not content:
            return jsonify({'error': 'content 不能为空'}), 400
        
        result = None
        
        # 根据配置选择提取方式
        if LLM_MODE == 'api':
            try:
                result = call_api_llm(content)
                logger.info(f"API LLM提取完成: type={result.get('type')}")
            except Exception as e:
                logger.warning(f"API LLM提取失败，回退到规则: {e}")
                
        elif LLM_MODE == 'local':
            try:
                result = extract_with_local_llm(content)
                logger.info(f"本地LLM提取完成: type={result.get('type')}")
            except Exception as e:
                logger.warning(f"本地LLM提取失败，回退到规则: {e}")
        
        # 如果 LLM 未启用或失败，使用规则提取
        if result is None:
            result = extract_with_rules(content)
            logger.info(f"规则提取完成: type={result.get('type')}")
        
        return jsonify(result)
        
    except Exception as e:
        logger.error(f"语义提取失败: {e}")
        return jsonify({'type': 'SKIP', 'reason': str(e)})


@app.route('/config', methods=['GET'])
def get_config():
    """获取当前配置"""
    emb_info = EMBEDDING_MODELS.get(EMBEDDING_MODEL, {})
    return jsonify({
        'llm_mode': LLM_MODE,
        'llm_api_provider': LLM_API_PROVIDER if LLM_MODE == 'api' else None,
        'llm_api_model': LLM_API_MODEL if LLM_MODE == 'api' else None,
        'llm_api_base': LLM_API_BASE if LLM_MODE == 'api' else None,
        'llm_api_configured': bool(LLM_API_KEY) if LLM_MODE == 'api' else False,
        'llm_local_model': LLM_LOCAL_MODEL,  # 始终返回，方便前端显示默认值
        'embedding_model': EMBEDDING_MODEL,
        'embedding_model_name': emb_info.get('name', EMBEDDING_MODEL),
        'embedding_dimension': emb_info.get('dimension', 512)
    })


@app.route('/config', methods=['POST'])
def update_config():
    """更新配置（运行时）"""
    global LLM_MODE, LLM_API_KEY, LLM_API_MODEL, LLM_API_BASE, LLM_API_PROVIDER, LLM_LOCAL_MODEL
    
    data = request.get_json()
    
    if 'llm_mode' in data:
        mode = data['llm_mode'].lower()
        if mode in ['disabled', 'local', 'api']:
            LLM_MODE = mode
            logger.info(f"LLM模式更新为: {LLM_MODE}")
    
    if 'llm_api_key' in data:
        LLM_API_KEY = data['llm_api_key']
        logger.info("API Key 已更新")
    
    if 'llm_api_model' in data:
        LLM_API_MODEL = data['llm_api_model']
        logger.info(f"API模型更新为: {LLM_API_MODEL}")
    
    if 'llm_api_base' in data:
        LLM_API_BASE = data['llm_api_base']
        logger.info(f"API Base更新为: {LLM_API_BASE}")
    
    if 'llm_api_provider' in data:
        LLM_API_PROVIDER = data['llm_api_provider']
        logger.info(f"API Provider更新为: {LLM_API_PROVIDER}")
    
    if 'llm_local_model' in data:
        LLM_LOCAL_MODEL = data['llm_local_model']
        logger.info(f"本地模型更新为: {LLM_LOCAL_MODEL}")
    
    # 保存配置到文件
    save_config()
    
    return jsonify({'status': 'ok', 'llm_mode': LLM_MODE, 'llm_local_model': LLM_LOCAL_MODEL})


# ========== Embedding 模型管理 API ==========
@app.route('/embedding/models', methods=['GET'])
def list_embedding_models():
    """列出所有支持的 embedding 模型"""
    models = []
    for model_id, info in EMBEDDING_MODELS.items():
        # 检查模型是否已下载
        downloaded = check_model_exists(model_id)
        
        # 确定状态：如果已下载，状态应该是 ready（忽略之前的错误缓存）
        if downloaded:
            status = 'ready'
            _model_download_status[model_id] = 'ready'  # 更新缓存
        else:
            status = _model_download_status.get(model_id, 'not_downloaded')
        
        models.append({
            'id': model_id,
            'name': info['name'],
            'dimension': info['dimension'],
            'size': info['size'],
            'description': info['description'],
            'download_size_mb': info.get('download_size_mb', 0),
            'is_current': model_id == EMBEDDING_MODEL,
            'downloaded': downloaded,
            'status': status
        })
    return jsonify({
        'models': models,
        'current': EMBEDDING_MODEL
    })


@app.route('/embedding/model', methods=['GET'])
def get_embedding_model_info():
    """获取当前 embedding 模型信息"""
    info = EMBEDDING_MODELS.get(EMBEDDING_MODEL, {})
    return jsonify({
        'id': EMBEDDING_MODEL,
        'name': info.get('name', EMBEDDING_MODEL),
        'dimension': info.get('dimension', 512),
        'size': info.get('size', 'unknown'),
        'description': info.get('description', ''),
        'loaded': _current_embedding_model_id == EMBEDDING_MODEL
    })


@app.route('/embedding/model', methods=['POST'])
def set_embedding_model():
    """切换 embedding 模型"""
    global EMBEDDING_MODEL, embedding_model, _current_embedding_model_id
    
    data = request.get_json()
    model_id = data.get('model_id')
    
    if not model_id:
        return jsonify({'error': 'model_id 不能为空'}), 400
    
    if model_id not in EMBEDDING_MODELS:
        return jsonify({'error': f'不支持的模型: {model_id}', 'available': list(EMBEDDING_MODELS.keys())}), 400
    
    if model_id == EMBEDDING_MODEL:
        return jsonify({'status': 'ok', 'message': '模型已是当前配置', 'model': model_id})
    
    # 切换模型
    old_model = EMBEDDING_MODEL
    EMBEDDING_MODEL = model_id
    
    # 标记需要重新加载
    _current_embedding_model_id = None
    
    # 保存配置
    save_config()
    
    logger.info(f"Embedding 模型切换: {old_model} -> {model_id}")
    
    return jsonify({
        'status': 'ok',
        'message': f'模型已切换为 {EMBEDDING_MODELS[model_id]["name"]}',
        'previous_model': old_model,
        'current_model': model_id,
        'dimension': EMBEDDING_MODELS[model_id]['dimension']
    })


@app.route('/embedding/model/download', methods=['POST'])
def download_embedding_model():
    """下载指定的 embedding 模型（后台任务，支持进度追踪）"""
    data = request.get_json()
    model_id = data.get('model_id')
    
    if not model_id:
        return jsonify({'error': 'model_id 不能为空'}), 400
    
    if model_id not in EMBEDDING_MODELS:
        return jsonify({'error': f'不支持的模型: {model_id}', 'available': list(EMBEDDING_MODELS.keys())}), 400
    
    # 检查是否已下载
    if check_model_exists(model_id):
        return jsonify({'status': 'already_exists', 'message': '模型已下载'})
    
    # 检查是否正在下载
    if _model_download_status.get(model_id) == 'downloading':
        return jsonify({'status': 'downloading', 'message': '模型正在下载中', 'progress': _model_download_progress.get(model_id, {})})
    
    # 初始化进度
    import time
    _model_download_progress[model_id] = {
        'downloaded_mb': 0,
        'total_mb': EMBEDDING_MODELS[model_id].get('download_size_mb', 0),
        'percent': 0,
        'speed_mbps': 0,
        'current_file': '准备中...',
        'files_done': 0,
        'files_total': 0,
        'error': None,
        'start_time': time.time()
    }
    
    # 在后台线程中下载（带进度追踪）
    def download_task():
        import time
        from pathlib import Path
        
        progress_ref = _model_download_progress[model_id]
        cache_path = Path(MODEL_CACHE) / f"models--{model_id.replace('/', '--')}"
        
        # 后台进度监控线程
        stop_monitor = [False]
        
        def monitor_progress():
            """轮询文件大小来计算下载进度"""
            last_size = 0
            last_time = time.time()
            logger.info(f"[Monitor] 启动进度监控, cache_path={cache_path}")
            
            while not stop_monitor[0]:
                try:
                    # 计算缓存目录总大小
                    total_size = 0
                    current_file = ""
                    path_exists = cache_path.exists()
                    
                    if path_exists:
                        for f in cache_path.rglob('*'):
                            if f.is_file():
                                total_size += f.stat().st_size
                                # 找到正在下载的文件（.incomplete 或最大的文件）
                                if '.incomplete' in str(f):
                                    current_file = f.name.replace('.incomplete', '')
                    
                    # 更新进度
                    progress_ref['downloaded_mb'] = round(total_size / (1024 * 1024), 2)
                    
                    # 计算百分比（使用预设总大小）
                    expected_mb = EMBEDDING_MODELS[model_id].get('download_size_mb', 1200)
                    progress_ref['total_mb'] = expected_mb
                    percent = min(99, int(total_size * 100 / (expected_mb * 1024 * 1024)))
                    progress_ref['percent'] = percent
                    
                    # 计算下载速度
                    now = time.time()
                    elapsed = now - last_time
                    if elapsed >= 1.0:
                        size_diff = total_size - last_size
                        speed = size_diff / elapsed / (1024 * 1024) if elapsed > 0 else 0
                        progress_ref['speed_mbps'] = round(speed, 2)
                        last_size = total_size
                        last_time = now
                    
                    if current_file:
                        progress_ref['current_file'] = current_file[:50]
                    
                    # 每秒输出一次进度日志
                    logger.info(f"[Monitor] {percent}% | {progress_ref['downloaded_mb']:.1f}MB | {progress_ref['speed_mbps']:.2f}MB/s | {current_file[:30]}")
                    
                except Exception as e:
                    logger.error(f"监控进度出错: {e}")
                
                time.sleep(1)  # 每秒更新一次
        
        # 启动监控线程
        monitor_thread = threading.Thread(target=monitor_progress)
        monitor_thread.daemon = True
        monitor_thread.start()
        
        try:
            _model_download_status[model_id] = 'downloading'
            logger.info(f"开始下载模型: {model_id}")
            
            # 设置在线模式和镜像
            os.environ.pop('HF_HUB_OFFLINE', None)
            os.environ.pop('TRANSFORMERS_OFFLINE', None)
            os.environ['HF_ENDPOINT'] = HF_MIRROR
            
            # 执行下载
            from huggingface_hub import snapshot_download
            snapshot_download(
                repo_id=model_id,
                cache_dir=MODEL_CACHE,
                etag_timeout=30,
                resume_download=True
            )
            
            # 停止监控
            stop_monitor[0] = True
            monitor_thread.join(timeout=2)
            
            _model_download_status[model_id] = 'ready'
            progress_ref['percent'] = 100
            progress_ref['current_file'] = '下载完成'
            logger.info(f"模型下载完成: {model_id}")
            
        except Exception as e:
            _model_download_status[model_id] = 'error'
            _model_download_progress[model_id]['error'] = str(e)
            logger.error(f"模型下载失败: {model_id}, 错误: {e}")
    
    thread = threading.Thread(target=download_task)
    thread.daemon = True
    thread.start()
    
    info = EMBEDDING_MODELS[model_id]
    return jsonify({
        'status': 'downloading',
        'message': f'开始下载 {info["name"]}（约 {info.get("download_size_mb", "?")}MB）',
        'model_id': model_id,
        'progress': _model_download_progress[model_id]
    })


@app.route('/embedding/model/download/status', methods=['GET'])
def get_download_status():
    """获取模型下载状态（含详细进度）"""
    model_id = request.args.get('model_id')
    
    if model_id:
        if model_id not in EMBEDDING_MODELS:
            return jsonify({'error': f'不支持的模型: {model_id}'}), 400
        downloaded = check_model_exists(model_id)
        status = _model_download_status.get(model_id, 'ready' if downloaded else 'not_downloaded')
        progress = _model_download_progress.get(model_id, {})
        
        # 计算预估剩余时间
        if status == 'downloading' and progress.get('speed_mbps', 0) > 0:
            remaining_mb = progress.get('total_mb', 0) - progress.get('downloaded_mb', 0)
            eta_seconds = remaining_mb / progress['speed_mbps']
            progress['eta_seconds'] = int(eta_seconds)
        
        return jsonify({
            'model_id': model_id,
            'downloaded': downloaded,
            'status': status,
            'progress': progress
        })
    else:
        # 返回所有模型的状态
        statuses = {}
        for mid in EMBEDDING_MODELS:
            downloaded = check_model_exists(mid)
            statuses[mid] = {
                'downloaded': downloaded,
                'status': _model_download_status.get(mid, 'ready' if downloaded else 'not_downloaded'),
                'progress': _model_download_progress.get(mid, {})
            }
        return jsonify({'models': statuses})


@app.route('/v1/chat/completions', methods=['POST'])
def chat_completions():
    """OpenAI 兼容的聊天接口 - 供 Java 后端调用本地模型"""
    import torch
    import time
    
    try:
        data = request.get_json()
        messages = data.get('messages', [])
        
        if not messages:
            return jsonify({'error': 'messages 不能为空'}), 400
        
        # 检查 LLM 是否可用
        if LLM_MODE == 'disabled':
            return jsonify({'error': 'LLM 未启用，请设置 LLM_MODE=local 或 api'}), 503
        
        # 使用 API 模式
        if LLM_MODE == 'api':
            # 转发到外部 API
            headers = {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {LLM_API_KEY}"
            }
            payload = {
                "model": LLM_API_MODEL,
                "messages": messages,
                "stream": False
            }
            resp = requests.post(
                f"{LLM_API_BASE.rstrip('/')}/chat/completions",
                headers=headers,
                json=payload,
                timeout=LLM_API_TIMEOUT
            )
            return jsonify(resp.json()), resp.status_code
        
        # 使用本地模型
        model, tokenizer = get_local_llm()
        if model is None:
            return jsonify({'error': '本地模型未加载'}), 503
        
        # 应用 chat 模板
        if is_qwen3_only:
            text = tokenizer.apply_chat_template(
                messages,
                tokenize=False,
                add_generation_prompt=True,
                enable_thinking=False  # 关闭思考模式
            )
        else:
            text = tokenizer.apply_chat_template(
                messages,
                tokenize=False,
                add_generation_prompt=True
            )
        
        inputs = tokenizer([text], return_tensors="pt")
        
        with torch.no_grad():
            outputs = model.generate(
                **inputs,
                max_new_tokens=512,
                temperature=0.7,
                top_p=0.8,
                top_k=20,
                do_sample=True,
                pad_token_id=tokenizer.eos_token_id
            )
        
        # 解码输出
        output_ids = outputs[0][inputs['input_ids'].shape[1]:].tolist()
        content = tokenizer.decode(output_ids, skip_special_tokens=True)
        
        # 返回 OpenAI 格式响应
        return jsonify({
            "id": f"chatcmpl-{int(time.time())}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": LLM_LOCAL_MODEL,
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": content
                },
                "finish_reason": "stop"
            }]
        })
        
    except Exception as e:
        logger.error(f"chat/completions 失败: {e}")
        return jsonify({'error': str(e)}), 500


if __name__ == '__main__':
    port = int(os.environ.get('EMBED_PORT', 8100))
    
    # 启动时初始化模型状态
    logger.info("初始化模型下载状态...")
    for model_id in EMBEDDING_MODELS:
        downloaded = check_model_exists(model_id)
        _model_download_status[model_id] = 'ready' if downloaded else 'not_downloaded'
        logger.info(f"  {model_id}: {'已下载' if downloaded else '未下载'}")
    
    logger.info(f"服务启动在端口 {port}, 模式: {LLM_MODE}")
    app.run(host='127.0.0.1', threaded=True, port=port)
