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