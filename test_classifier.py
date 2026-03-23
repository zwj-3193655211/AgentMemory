# 完整测试分类逻辑

# 已解决标记词
RESOLVED_MARKERS = [
    '后来发现', '原来是', '原因找到了', '问题是', '解决办法是', '解决方法是',
    '这样就行', '就好了', '改好了', '修好了', '搞定了', '弄好了',
    '是因为', '原来是因为', '问题出在', '根本原因是', '所以要用', '得用'
]

# 求助标记词
HELP_REQUEST_MARKERS = [
    '请帮我', '帮我', '求助', '请问', '怎么解决', '如何解决',
    '怎么办', '为什么会', '请修复', '请检查', '帮我看看', '能不能'
]

# 技能/步骤标记词
SKILL_MARKERS = ['步骤', '流程', '就几步', '分几步', '其实就', '总共', '第一步', '首先']

# 最佳实践/经验标记词
PRACTICE_MARKERS = ['有个坑', '踩坑', '注意', '记得', '别忘了', '经验是', '建议', '推荐']

def is_help_request(content):
    for marker in HELP_REQUEST_MARKERS:
        if marker in content:
            return True
    return False

def has_resolved_marker(content):
    for marker in RESOLVED_MARKERS:
        if marker in content:
            return True
    return False

def has_skill_markers(content):
    for marker in SKILL_MARKERS:
        if marker in content:
            if '步' in content or '先' in content or '然后' in content or '最后' in content:
                return True
    return False

def has_practice_markers(content):
    for marker in PRACTICE_MARKERS:
        if marker in content:
            return True
    return False

def has_preference_markers(content):
    return any(m in content for m in ['我喜欢', '我习惯', '我偏好', '我不用', '我更喜欢', '我通常'])

def classify(content):
    if is_help_request(content):
        if has_preference_markers(content):
            return 'USER_PROFILE'
        return 'SKIP'
    
    if has_skill_markers(content):
        return 'SKILL'
    
    if has_practice_markers(content):
        return 'BEST_PRACTICE'
    
    if has_preference_markers(content):
        return 'USER_PROFILE'
    
    if has_resolved_marker(content):
        return 'ERROR_CORRECTION'
    
    return 'SKIP'

# 测试用例
test_cases = [
    ('我的opencode突然无法使用请帮我修复', 'SKIP'),
    ('找不到 server.js，后来发现是路径问题，移到正确目录就好了', 'ERROR_CORRECTION'),
    ('双击bat会闪退请修复', 'SKIP'),
    ("'node' 不是内部或外部命令。解决方法是配置环境变量", 'ERROR_CORRECTION'),
    ('docker部署其实就三步：先写Dockerfile，然后docker build打个镜像，最后docker run跑起来就行', 'SKILL'),
    ('我喜欢用npm不用yarn', 'USER_PROFILE'),
    ('Windows下用PyInstaller打包有个坑，它默认用的是系统Python不是你激活的那个环境，所以要用conda run -n env_name pyinstaller这样才行', 'BEST_PRACTICE'),
    ('为什么生成音频生成总是失败而且好的错误请分析是否有更好的方法解决', 'SKIP'),
    ('运行成功了', 'SKIP'),
    ('踩坑记录：打包exe的时候遇到过几个问题，一个是conda环境的问题要用conda run指定', 'BEST_PRACTICE'),
]

print('分类测试结果：')
print('-' * 70)
correct = 0
for content, expected in test_cases:
    result = classify(content)
    status = 'OK' if expected == result else 'X'
    if expected == result:
        correct += 1
    print(f'[{status}] "{content[:45]}..."')
    print(f'    期望: {expected}, 结果: {result}')
    print()

print(f'正确率: {correct}/{len(test_cases)} = {correct/len(test_cases)*100:.0f}%')