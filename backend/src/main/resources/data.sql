-- ============================================================
-- 预置数据（幂等）：敏感词词典 + 初始合成语料
-- 词典词均已规范化：小写、仅字母数字/中文字符（无空格标点）
-- ============================================================

-- ---------- 敏感词词典（通用客服质检场景） ----------
INSERT IGNORE INTO dictionary_words (word, category, severity, source, enabled, hit_count, created_at, updated_at) VALUES
('fuck',             'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('fucking',          'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('fucker',           'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('motherfucker',     'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('bitch',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('asshole',          'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('bastard',          'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('shit',             'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('shithead',         'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('bullshit',         'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('cunt',             'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('dick',             'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('dickhead',         'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('cock',             'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('douchebag',        'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('wanker',           'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('prick',            'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('twat',             'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('bollocks',         'INSULT',        'LOW',    'MANUAL', 1, 0, NOW(), NOW()),
('slut',             'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('whore',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('damn',             'INSULT',        'LOW',    'MANUAL', 1, 0, NOW(), NOW()),
('crap',             'INSULT',        'LOW',    'MANUAL', 1, 0, NOW(), NOW()),
('stupid',           'INSULT',        'LOW',    'MANUAL', 1, 0, NOW(), NOW()),
('idiot',            'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('moron',            'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('dumbass',          'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('jackass',          'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('jerk',             'INSULT',        'LOW',    'MANUAL', 1, 0, NOW(), NOW()),
('妈的',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('傻逼',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('操你',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('贱人',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('白痴',              'INSULT',        'LOW',    'MANUAL', 1, 0, NOW(), NOW()),
('nigger',           'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('nigga',            'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('faggot',           'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('fag',              'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('retard',           'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('retarded',         'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('spic',             'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('chink',            'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('kike',             'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('coon',             'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('killyou',          'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('gonnakill',        'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('iwillkill',        'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('illkill',          'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('shootyou',         'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('shootyour',        'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('beatyouup',        'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('stabyou',          'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('iwillfindyou',     'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('youaredead',       'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('youwillregret',    'THREAT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('去死',              'THREAT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('stalk',            'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('stalking',         'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('stalker',          'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('harass',           'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('harassing',        'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('fuckyou',          'HARASSMENT',    'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('fuckoff',          'HARASSMENT',    'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('screwyou',         'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('pissoff',          'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('shutup',           'HARASSMENT',    'LOW',    'MANUAL', 1, 0, NOW(), NOW()),
('suckmydick',       'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('suckmycock',       'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('blowjob',          'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('rape',             'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('sendnudes',        'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('nicetits',         'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('scam',             'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('scamming',         'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('scammer',          'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('moneylaundering',  'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('wirememoney',      'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('sendmemoney',      'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('youoweme',         'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('paymenow',         'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('guaranteedprofit', 'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('riskfree',         'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('doubleyourmoney',  'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('pyramidscheme',    'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('ponzi',            'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('fakeinvoice',      'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('giftcard',         'FRAUD',         'LOW',    'MANUAL', 1, 0, NOW(), NOW()),
('creditcardnumber', 'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('cardnumber',       'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('carddetails',      'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('cvv',              'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('socialsecurity',   'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('ssn',              'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('passportnumber',   'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('bankaccountnumber','PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('password',         'PRIVACY',       'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('pincode',          'PRIVACY',       'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('logincredentials', 'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('身份证号',           'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('银行卡号',           'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW());

-- ---------- 中文敏感词 ----------
-- 客服通话以中文为主，而 DFA 是子串匹配 + 无词边界（中文无空格，天然适配）。
-- 选词原则：优先「只可能出现在攻击/违规语境」的词，避免误伤正常业务用语
--   （例如不收录「垃圾」「转账」「死」这类高频中性词；「转账到安全账户」只收「安全账户」）。
-- 语义歧义（引用、玩笑、业务术语）由第二段 AI 复筛兜底，误报不会直接定性。
INSERT IGNORE INTO dictionary_words (word, category, severity, source, enabled, hit_count, created_at, updated_at) VALUES
-- 辱骂
('傻b',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('沙比',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('煞笔',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('煞比',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('智障',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('脑残',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('弱智',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('蠢货',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('蠢猪',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('废物',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('饭桶',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('混蛋',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('王八蛋',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('狗东西',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('狗杂种',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('畜生',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('贱货',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('贱骨头',            'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('滚蛋',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('滚出去',            'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('给我滚',            'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('他妈',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('你妈的',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('去你妈',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('草泥马',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('操你妈',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('装逼',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('二逼',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('傻叉',              'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('神经病',            'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('死胖子',            'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
-- 威胁
('弄死你',            'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('打死你',            'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('砍死你',            'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('杀了你',            'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('灭了你',            'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('整死你',            'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('弄死他',            'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('打断你的腿',        'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('找人收拾你',        'THREAT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('让你好看',          'THREAT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('走着瞧',            'THREAT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('收拾你',            'THREAT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('报复你',            'THREAT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('你等着',            'THREAT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
-- 歧视
('乡巴佬',            'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('土包子',            'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('外地佬',            'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('老不死',            'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('死老太婆',          'DISCRIMINATION','HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('乡下人',            'DISCRIMINATION','MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('残废',              'DISCRIMINATION','MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
-- 骚扰
('骚扰你',            'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('天天骚扰',          'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('纠缠你',            'HARASSMENT',    'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
-- 色情骚扰
('约炮',              'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('一夜情',            'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('特殊服务',          'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('色情服务',          'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('裸聊',              'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('陪睡',              'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('包养',              'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('陪我睡',            'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('性服务',            'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('性骚扰',            'SEXUAL',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
-- 诈骗诱导
('刷单',              'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('安全账户',          'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('解冻费',            'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('涉嫌洗钱',          'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('冒充公检法',        'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('稳赚不赔',          'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('高额回报',          'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('把验证码告诉',      'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('验证码给我',        'FRAUD',         'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('内幕消息',          'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('内部消息',          'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('返利',              'FRAUD',         'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
-- ASR 同音/近音误写变体
-- 实测：Whisper 会把「傻逼」转写成「少逼」（shǎ bī ↔ shǎo bī），而流水线是
-- 「DFA 命中才进 AI 复筛」，漏检 = 该条录音直接判合规、AI 永远看不到。
-- 因此高频脏话的同音写法必须一并收录，这是关键词初筛在 ASR 文本上的必要冗余。
('少逼',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('傻比',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('傻壁',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('杀比',              'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('草你妈',            'INSULT',        'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('妈的个',            'INSULT',        'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
-- 隐私泄露
('银行卡密码',        'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('短信验证码',        'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('密码告诉我',        'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('密码是多少',        'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('身份证照片',        'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('身份证正反面',      'PRIVACY',       'HIGH',   'MANUAL', 1, 0, NOW(), NOW()),
('家庭住址',          'PRIVACY',       'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW()),
('社保号',            'PRIVACY',       'MEDIUM', 'MANUAL', 1, 0, NOW(), NOW());

-- ---------- 初始合成语料（few-shot 参考，人工复检积累后自然替换） ----------
INSERT INTO corpus_entries (label, transcript, violation_type, reason, source, recording_id, created_at)
SELECT 'VIOLATION',
       'If you do not give me a refund right now, I swear I will find you and fuck you up. You stupid bitch, stop wasting my time.',
       'INSULT', 'Direct abuse and threat toward the agent', 'SEED', -10001, NOW()
WHERE NOT EXISTS (SELECT 1 FROM corpus_entries WHERE recording_id = -10001);

INSERT INTO corpus_entries (label, transcript, violation_type, reason, source, recording_id, created_at)
SELECT 'VIOLATION',
       'I do not want to talk to that faggot supervisor, you people are all the same, go back to your country.',
       'DISCRIMINATION', 'Homophobic slur and xenophobic remark', 'SEED', -10002, NOW()
WHERE NOT EXISTS (SELECT 1 FROM corpus_entries WHERE recording_id = -10002);

INSERT INTO corpus_entries (label, transcript, violation_type, reason, source, recording_id, created_at)
SELECT 'VIOLATION',
       'Just send me the gift card numbers and I guarantee you will double your account balance today, this is completely risk free.',
       'FRAUD', 'Fraudulent guarantee and gift-card inducement', 'SEED', -10003, NOW()
WHERE NOT EXISTS (SELECT 1 FROM corpus_entries WHERE recording_id = -10003);

INSERT INTO corpus_entries (label, transcript, violation_type, reason, source, recording_id, created_at)
SELECT 'COMPLIANT',
       'I understand this is frustrating, damn, but the system is temporarily down. Sir, I would never scam you; this is a standard verification step.',
       NULL, 'Keyword false positive: mild frustration and denial of scam', 'SEED', -10004, NOW()
WHERE NOT EXISTS (SELECT 1 FROM corpus_entries WHERE recording_id = -10004);

INSERT INTO corpus_entries (label, transcript, violation_type, reason, source, recording_id, created_at)
SELECT 'COMPLIANT',
       'Please confirm your password only to verify the account, we never ask for your full credit card number over the phone unless you initiated a payment.',
       NULL, 'Handling sensitive data in a compliant, reassuring way', 'SEED', -10005, NOW()
WHERE NOT EXISTS (SELECT 1 FROM corpus_entries WHERE recording_id = -10005);

-- ---------- 中文语料（业务实际语种，供 AI 复筛做 few-shot） ----------
INSERT INTO corpus_entries (label, transcript, violation_type, reason, source, recording_id, created_at)
SELECT 'VIOLATION',
       '【坐席】你这个人怎么这么傻逼，说了多少次了，滚蛋，别再来电话了。',
       'INSULT', '坐席直接辱骂客户，攻击性明确', 'SEED', -10006, NOW()
WHERE NOT EXISTS (SELECT 1 FROM corpus_entries WHERE recording_id = -10006);

INSERT INTO corpus_entries (label, transcript, violation_type, reason, source, recording_id, created_at)
SELECT 'VIOLATION',
       '【客户】你要是不给我退钱，我就找人收拾你，你给我等着。',
       'THREAT', '客户威胁坐席人身安全；双方违规均需标记，理由中注明说话人', 'SEED', -10007, NOW()
WHERE NOT EXISTS (SELECT 1 FROM corpus_entries WHERE recording_id = -10007);

INSERT INTO corpus_entries (label, transcript, violation_type, reason, source, recording_id, created_at)
SELECT 'COMPLIANT',
       '【坐席】先生您别着急，我们不会让您转账到任何账户，也不会问您的短信验证码，这些都是诈骗话术，请您千万不要相信。',
       NULL, '命中「短信验证码」，但坐席是在做反诈提醒而非索要，属误报', 'SEED', -10008, NOW()
WHERE NOT EXISTS (SELECT 1 FROM corpus_entries WHERE recording_id = -10008);