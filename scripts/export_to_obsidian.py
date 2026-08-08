# -*- coding: utf-8 -*-
"""
AgentMemory → Obsidian 导出脚本

从 Java API 拉取记忆库数据，写入 Obsidian vault 为双链笔记 + 索引 + Canvas 知识图谱。

输出结构：
  SuperMemory/agent_memory/exported/
    experiences/
      best_practice_<id>.md
      error_correction_<id>.md
    skills/<id>.md
    profiles/<id>.md
    sessions/<id>.md
    index.md                  (双链入口)
    agent_memory_index.base   (属性数据库)
    knowledge_graph.canvas    (JSON Canvas 知识图谱)

Usage:
  python export_to_obsidian.py [--vault C:/Users/31936/SuperMemory] [--api http://localhost:8082/api]
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path
from typing import List, Dict, Any

# 默认后端 API
DEFAULT_API = "http://localhost:8082/api"
# 默认 vault 位置
DEFAULT_VAULT = r"C:\Users\31936\SuperMemory"
# 导出子目录
EXPORT_SUBDIR = "agent_memory/exported"


def http_get(api: str, endpoint: str) -> Any:
    """GET 请求并返回 JSON"""
    url = f"{api}{endpoint}"
    try:
        with urllib.request.urlopen(url, timeout=60) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.URLError as e:
        print(f"[ERROR] GET {url} failed: {e}")
        return None


def sanitize_for_filename(s: str) -> str:
    """去除文件名非法字符"""
    return re.sub(r'[\\/:*?"<>|\n\r]', "_", s).strip()[:80]


def write_md(path: Path, content: str):
    """写 Markdown 文件，UTF-8 无 BOM"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def to_wikilink(text: str) -> str:
    """文本转 wikilink：去除特殊字符，不存在时使用 [[text|text]] 格式"""
    return text.replace("]", "\\]").replace("[", "\\[")


def export_experiences(api: str, base: Path) -> int:
    """导出实践经验（错误纠正 + 最佳实践）"""
    print("\n[1/4] 导出实践经验...")
    items = http_get(api, "/experiences") or []
    count = 0
    for item in items:
        item_id = item.get("id", "unknown")
        item_type = item.get("type", "best_practice")
        title = item.get("title", "Untitled")
        type_label = "最佳实践" if item_type == "best_practice" else "错误纠正"
        scenario = item.get("scenario", "") or ""
        practice = item.get("practice", "") or ""
        rationale = item.get("rationale", "") or ""
        example = item.get("example", "") or ""
        tags = item.get("tags") or []
        source_session = item.get("sourceSession") or ""
        created_at = item.get("createdAt")

        # wikilink 关联
        wikilinks = []
        if tags:
            wikilinks.append(f"**tags**: {', '.join(['#' + t for t in tags])}")
        if source_session:
            wikilinks.append(f"**来源会话**: [[sessions/{source_session}]]")

        date_str = ""
        if isinstance(created_at, (int, float)):
            date_str = time.strftime("%Y-%m-%d", time.gmtime(created_at / 1000))

        frontmatter = "---\n"
        frontmatter += f"id: {item_id}\n"
        frontmatter += f"type: {item_type}\n"
        frontmatter += f"title: {title}\n"
        if date_str:
            frontmatter += f"created: {date_str}\n"
        if tags:
            frontmatter += f"tags: [{', '.join(tags)}]\n"
        if source_session:
            frontmatter += f"source_session: \"{source_session}\"\n"
        frontmatter += "---\n\n"

        body = f"# {title}\n\n"
        body += f"> {type_label}\n\n"
        body += f"## 场景\n\n{scenario}\n\n"
        body += f"## 做法\n\n{practice}\n\n"
        if rationale:
            body += f"## 原因\n\n{rationale}\n\n"
        if example:
            body += f"## 示例\n\n```\n{example}\n```\n\n"
        if wikilinks:
            body += "## 关联\n\n" + "\n".join(wikilinks) + "\n"

        prefix = "best_practice" if item_type == "best_practice" else "error_correction"
        filename = f"{prefix}_{sanitize_for_filename(item_id)}.md"
        write_md(base / "experiences" / filename, frontmatter + body)
        count += 1
    print(f"  ✓ 导出 {count} 条经验")
    return count


def export_skills(api: str, base: Path) -> int:
    """导出技能沉淀"""
    print("\n[2/4] 导出技能沉淀...")
    items = http_get(api, "/skills") or []
    count = 0
    for item in items:
        item_id = item.get("id", "unknown")
        title = item.get("title") or "Untitled"
        description = item.get("description") or ""
        steps = item.get("steps") or ""
        skill_type = item.get("skillType") or ""
        tags = item.get("tags") or []
        status = item.get("status") or "approved"
        created_at = item.get("createdAt")

        # 解析 steps（JSON 字符串）
        steps_list = []
        if steps:
            try:
                steps_list = json.loads(steps) if isinstance(steps, str) else steps
            except Exception:
                # 简单按行分割
                steps_list = [s.strip() for s in steps.split("\n") if s.strip()]

        date_str = ""
        if isinstance(created_at, (int, float)):
            date_str = time.strftime("%Y-%m-%d", time.gmtime(created_at / 1000))

        frontmatter = "---\n"
        frontmatter += f"id: {item_id}\n"
        frontmatter += f"type: skill\n"
        frontmatter += f"title: {title}\n"
        if date_str:
            frontmatter += f"created: {date_str}\n"
        if skill_type:
            frontmatter += f"skill_type: {skill_type}\n"
        if tags:
            frontmatter += f"tags: [{', '.join(tags)}]\n"
        frontmatter += f"status: {status}\n"
        frontmatter += "---\n\n"

        body = f"# {title}\n\n"
        body += f"> 技能沉淀\n\n"
        if skill_type:
            body += f"**类型**: {skill_type}\n\n"
        if description:
            body += f"## 描述\n\n{description}\n\n"
        if steps_list:
            body += "## 步骤\n\n"
            for i, step in enumerate(steps_list, 1):
                body += f"{i}. {step}\n"
            body += "\n"
        if tags:
            body += f"**Tags**: {', '.join(['#' + t for t in tags])}\n\n"

        skip_statuses = ["rejected"]
        if status in skip_statuses:
            continue
        filename = f"skill_{sanitize_for_filename(item_id)}.md"
        write_md(base / "skills" / filename, frontmatter + body)
        count += 1
    print(f"  ✓ 导出 {count} 条技能")
    return count


def export_profiles(api: str, base: Path) -> int:
    """导出用户画像（按 source_agent 分组）"""
    print("\n[3/4] 导出用户画像...")
    items = http_get(api, "/profiles") or []

    # 按 agent 分组
    groups: Dict[str, List[dict]] = {}
    for p in items:
        agent = p.get("sourceAgent") or "manual"
        groups.setdefault(agent, []).append(p)

    count = 0
    for agent, profiles in groups.items():
        # 创建分组首页
        write_md(base / "profiles" / f"agent_{sanitize_for_filename(agent)}.md",
                 f"# {agent} - 用户画像\n\n"
                 f"来源：{agent} agent 的记忆文件\n"
                 f"条数：{len(profiles)}\n\n"
                 f"## 画像列表\n\n" +
                 "\n".join(f"- [[profile_{sanitize_for_filename(p['id'])}|{p.get('title', 'untitled')[:40]}]]"
                           for p in profiles) +
                 "\n")
        for p in profiles:
            item_id = p.get("id", "unknown")
            title = p.get("title") or ""
            category = p.get("category") or ""
            items_json = p.get("items")
            # 解析 items JSON
            content_text = ""
            if items_json:
                try:
                    parsed = json.loads(items_json) if isinstance(items_json, str) else items_json
                    parts = []
                    for it in parsed:
                        if isinstance(it, dict):
                            c = it.get("content", "")
                            if c:
                                parts.append(c)
                        else:
                            parts.append(str(it))
                    content_text = "\n\n---\n\n".join(parts)
                except Exception:
                    content_text = str(items_json)
            updated_at = p.get("updatedAt")
            date_str = ""
            if isinstance(updated_at, (int, float)):
                date_str = time.strftime("%Y-%m-%d", time.gmtime(updated_at / 1000))

            frontmatter = "---\n"
            frontmatter += f"id: {item_id}\n"
            frontmatter += f"type: profile\n"
            frontmatter += f"source_agent: {agent}\n"
            if date_str:
                frontmatter += f"updated_at: {date_str}\n"
            if category:
                frontmatter += f"category: {category}\n"
            frontmatter += "---\n\n"

            body = f"# {title[:60]}\n\n"
            body += f"> 来源: {agent} | 类别: {category}\n\n"
            body += "## 内容\n\n"
            body += content_text + "\n\n"
            body += "## 关联\n\n"
            body += f"- 来源: [[agent_{agent}]]\n"

            filename = f"profile_{sanitize_for_filename(item_id)}.md"
            write_md(base / "profiles" / filename, frontmatter + body)
            count += 1
    print(f"  ✓ 导出 {count} 条画像（{len(groups)} 个 agent）")
    return count


def export_sessions(api: str, base: Path) -> int:
    """导出会话"""
    print("\n[4/4] 导出会话...")
    items = http_get(api, "/sessions?limit=500") or []
    count = 0
    for item in items:
        item_id = item.get("id", "unknown")
        title = item.get("title") or "(无标题)"
        agent_type = item.get("agentType") or ""
        project_path = item.get("projectPath") or ""
        message_count = item.get("messageCount") or 0
        created_at = item.get("createdAt")

        date_str = ""
        if isinstance(created_at, (int, float)):
            date_str = time.strftime("%Y-%m-%d", time.gmtime(created_at / 1000))

        frontmatter = "---\n"
        frontmatter += f"id: {item_id}\n"
        frontmatter += f"type: session\n"
        frontmatter += f"agent: {agent_type}\n"
        frontmatter += f"title: {title}\n"
        if date_str:
            frontmatter += f"created: {date_str}\n"
        if project_path:
            frontmatter += f"project_path: \"{project_path}\"\n"
        frontmatter += f"message_count: {message_count}\n"
        frontmatter += "---\n\n"

        body = f"# {title}\n\n"
        body += f"> 会话 | {agent_type}\n\n"
        body += f"**项目**: {project_path}\n"
        body += f"**消息数**: {message_count}\n"
        body += f"**创建**: {date_str}\n\n"
        body += "## 关联\n\n"
        body += f"- agent: [[agent_{agent_type}]]\n"

        filename = f"ses_{sanitize_for_filename(item_id)}.md"
        write_md(base / "sessions" / filename, frontmatter + body)
        count += 1
    print(f"  ✓ 导出 {count} 个会话")
    return count


def export_index(base: Path, stats: Dict[str, int]):
    """导出入口索引"""
    print("\n[+] 导出索引页...")
    md = "# AgentMemory 知识库索引\n\n"
    md += "> 所有记忆自动从 AgentMemory 同步。修改源数据后重新运行 `python scripts/export_to_obsidian.py` 即可同步。\n\n"
    md += f"## 数据概览\n\n"
    md += f"- **实践经验**: {stats['experiences']} 条（双链索引见 [[agent_memory_index.base]]）\n"
    md += f"- **技能沉淀**: {stats['skills']} 条\n"
    md += f"- **用户画像**: {stats['profiles']} 条（按 agent 分组）\n"
    md += f"- **会话**: {stats['sessions']} 条\n\n"
    md += "## 视图\n\n"
    md += "- **数据库视图**: [[agent_memory_index.base]]（按标签/类型/创建时间筛选）\n"
    md += "- **知识图谱**: [[knowledge_graph.canvas]]（可视化节点关系）\n\n"
    md += "## 目录导航\n\n"
    md += "- `experiences/` - 实践经验（最佳实践 + 错误纠正）\n"
    md += "- `skills/` - 技能沉淀\n"
    md += "- `profiles/` - 用户画像（按 agent 分子目录）\n"
    md += "- `sessions/` - 会话\n"

    write_md(base / "index.md", md)
    print("  ✓ index.md")


def export_base_index(base: Path):
    """导出 Obsidian Bases 索引（.base 文件）"""
    print("\n[+] 导出 Obsidian Bases 索引...")

    base_content = """filters:
  or:
    - file.hasTag("type/experience")
    - file.hasTag("type/skill")
    - file.hasTag("type/profile")
    - file.hasTag("type/session")

formulas:
  experience_type: 'if(file.hasTag("type/error-correction"), "错误纠正", if(file.hasTag("type/best-practice"), "最佳实践", "其他"))'
  source_agent: 'x = file.tags; if(x) "agent" else "manual"'

properties:
  type:
    displayName: 类型
  experience_type:
    displayName: 经验类别
  source_agent:
    displayName: 来源
  tags:
    displayName: 标签
  file.mtime:
    displayName: 更新时间

views:
  - type: table
    name: 全部记忆
    order:
      - file.name
      - type
      - experience_type
      - source_agent
      - tags
      - file.mtime

  - type: table
    name: 最佳实践
    filter:
      and:
        - file.hasTag("type/experience")
        - file.hasTag("type/best-practice")
    order:
      - file.name
      - tags
      - file.mtime

  - type: table
    name: 错误纠正
    filter:
      and:
        - file.hasTag("type/experience")
        - file.hasTag("type/error-correction")
    order:
      - file.name
      - tags
      - file.mtime

  - type: table
    name: 技能沉淀
    filter:
      and:
        - file.hasTag("type/skill")
    order:
      - file.name
      - skill_type
      - tags
      - file.mtime

  - type: table
    name: 按来源 agent
    groupBy:
      property: source_agent
    order:
      - file.name
      - type
      - file.mtime
"""
    write_md(base / "agent_memory_index.base", base_content)
    print("  ✓ agent_memory_index.base")


def export_canvas(base: Path, stats: Dict[str, int]):
    """导出 JSON Canvas 知识图谱"""
    print("\n[+] 导出知识图谱 Canvas...")

    nodes = []
    edges = []
    grid_x = 0
    grid_y = 0

    # 中心节点：索引
    nodes.append({
        "id": "0000000000000001",
        "type": "text",
        "x": 0, "y": 0,
        "width": 300, "height": 100,
        "color": "5",
        "text": "# AgentMemory 知识图谱\n\n8 个 agent 记忆 + 会话 + 经验 + 技能\n\n总计:\n- 经验 " + str(stats['experiences']) + " 条\n- 技能 " + str(stats['skills']) + " 条\n- 画像 " + str(stats['profiles']) + " 条\n- 会话 " + str(stats['sessions']) + " 条"
    })

    # 4 类节点（垂直堆叠）
    types = [
        (1, "经验", "experiences", "2", "left"),
        (2, "技能", "skills", "3", "right"),
        (3, "画像", "profiles", "4", "right"),
        (4, "会话", "sessions", "6", "left"),
    ]
    label_to_id = {}
    y_offset = -800
    for idx, label, dir_name, color, side in types:
        node_id = f"{idx:016d}"
        label_to_id[label] = node_id
        nodes.append({
            "id": node_id,
            "type": "group",
            "x": -600 if side == "left" else 600,
            "y": y_offset,
            "width": 400, "height": 300,
            "label": f"{label} （{stats.get(dir_name.replace('s', ''), 0)}）",
            "color": color
        })
        # 中心节点到这个组的连线
        edges.append({
            "id": f"edge_center_{label}",
            "fromNode": "0000000000000001",
            "toNode": node_id,
            "fromSide": "left" if side == "right" else "right",
            "toSide": side,
            "label": label,
            "color": "5"
        })
        counter += 1
        y_offset += 400

    # 4 类间的关系
    relations = [
        ("经验", "技能", "←→", "经验沉淀为技能"),
        ("经验", "会话", "←→", "从会话提取"),
        ("技能", "会话", "←→", "运用于会话"),
        ("画像", "会话", "←→", "影响会话"),
    ]
    for src, dst, label, desc in relations:
        edges.append({
            "id": f"edge_{src}_{dst}",
            "fromNode": label_to_id.get(src, "0000000000000000"),
            "toNode": label_to_id.get(dst, "0000000000000000"),
            "label": desc,
            "color": "4"
        })

    canvas = {
        "nodes": nodes,
        "edges": edges
    }
    write_md(base / "knowledge_graph.canvas", json.dumps(canvas, ensure_ascii=False, indent=2))
    print(f"  ✓ knowledge_graph.canvas ({len(nodes)} nodes, {len(edges)} edges)")


def export_agent_index_files(base: Path):
    """为每个 agent 生成汇总索引（被 wikilink 引用）"""
    agents = ["hermes", "pi", "claude", "workbuddy", "minimax", "mavis", "marvis", "codex"]
    for agent in agents:
        path = base / ".." / f"agent_{agent}.md"
        if path.exists():
            continue
        # 在 profiles 目录下生成 agent 索引
        content = f"# {agent} - Agent 入口\n\n"
        content += f"## 来源\n\n"
        content += f"该 agent 的记忆文件被自动导入到 [[agent_memory/exported/index|AgentMemory 知识库]]\n\n"
        content += f"## 关联条目\n\n"
        content += f"- 画像: [[agent_memory/exported/profiles/agent_{agent}|{agent} 画像汇总]]\n"
        if os.path.exists(base / "profiles" / f"agent_{agent}.md"):
            content += f"- 画像列表: 见 [[agent_memory/exported/profiles/agent_{agent}]]\n"
        content += f"- 会话: 见 [[agent_memory/exported/sessions/]]\n"
        write_md(base / ".." / f"agent_{agent}.md", content)


def main():
    parser = argparse.ArgumentParser(description="AgentMemory → Obsidian 导出")
    parser.add_argument("--vault", default=DEFAULT_VAULT, help="Obsidian vault 路径")
    parser.add_argument("--api", default=DEFAULT_API, help="AgentMemory API base URL")
    args = parser.parse_args()

    vault = Path(args.vault)
    base = vault / EXPORT_SUBDIR
    base.mkdir(parents=True, exist_ok=True)

    print(f"=== AgentMemory → Obsidian 导出 ===")
    print(f"Vault: {vault}")
    print(f"Base:  {base}")
    print(f"API:   {args.api}")

    # 验证 API
    health = http_get(args.api, "/stats")
    if not health:
        print(f"[FATAL] 无法连接 API: {args.api}")
        sys.exit(1)
    print(f"\nAPI ✓ sessions={health.get('sessions',0)} messages={health.get('messages',0)} skills={health.get('skills',0)}")

    # 导出 4 类
    stats = {
        "experiences": export_experiences(args.api, base),
        "skills": export_skills(args.api, base),
        "profiles": export_profiles(args.api, base),
        "sessions": export_sessions(args.api, base),
    }

    # 索引与图谱
    export_index(base, stats)
    export_base_index(base)
    export_canvas(base, stats)
    export_agent_index_files(base)

    print(f"\n=== 完成 ===")
    print(f"总计: {stats['experiences']} 经验 + {stats['skills']} 技能 + {stats['profiles']} 画像 + {stats['sessions']} 会话")
    print(f"输出: {base}")
    print(f"打开 Obsidian，在 vault 中查看 [[agent_memory/exported/index]]")


if __name__ == "__main__":
    main()
