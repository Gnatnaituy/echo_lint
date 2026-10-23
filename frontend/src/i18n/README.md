# i18n（中 / 英）

轻量、零依赖的国际化模块。全站文案（页头 / 侧栏 / 内容页 / 通用工具）统一维护在
`src/i18n/messages/*.js`，每个模块同时导出 `zh` / `en`，由 `zh.js` / `en.js` 汇总。

## 用法

```js
// Vue 组件（模板里可直接用 t，语言切换时自动重渲染）
import { useI18n } from '../i18n'
const { t, tn } = useI18n()

// 纯 JS 工具 / 常量（参与 node --test，必须带 index.js，Node ESM 不支持目录导入）
import { t } from '../i18n/index.js'

t('nav.dashboard')                  // 当前语言 → 中文回退 → key 本身
t('login.welcome', { name: '张三' }) // 支持 {param} 插值
tn('apiKeys.count', n)              // 复数感知：按数量选 <key>.one / <key>.other
setLocale('en')                     // 切换并持久化到 localStorage（echolint.locale）
```

- 缺失 key：英文缺失自动回退中文；中文也缺失时原样返回 key，便于发现漏翻译。
- `setLocale` 会同步 `<html lang>`（zh → `zh-CN`，en → `en`），并在浏览器中用
  localStorage 记住选择；隐私模式下写入失败会自动忽略。
- `tests/i18n.test.mjs` 校验 zh / en key 完全对齐、英文无中文残留、源码引用 key 存在、回退与持久化行为；
  `tests/i18n-coverage.test.mjs` 用 `scripts/i18n-audit.mjs` 守卫内容源码无硬编码中文。
- 数量文案：英文需要单复数时同时提供 `<key>.one` / `<key>.other`，用 `tn(key, count)` 读取；
  `tn` 在缺少变体时自动回退普通 `t(key, { count })`（中文两条变体可写同一文案）。

## 目录与归属

```
src/i18n/
├── index.js            # t / tn / setLocale / useI18n 等运行时
├── zh.js / en.js       # 汇总（勿直接改，改 messages/）
├── messages/
│   ├── index.js        # 模块注册表（新增域时在这里登记）
│   ├── base.js         # 基础：页头 / 侧栏 / 登录 / 通用按钮 / 大屏 / 时间 / 接口错误
│   ├── meta.js         # 枚举标签：状态 / 违规类型 / 角色 / 优先级 / 审计动作 / 索引状态…
│   ├── dashboard.js    # 工作台
│   ├── recordings.js   # 录音管理
│   ├── components.js   # 共享组件（播放器 / 转写 / 指派弹窗…）
│   ├── review.js       # 人工复检
│   ├── reports.js      # 报表
│   ├── dictionary.js   # 敏感词库
│   ├── corpus.js       # 语料库
│   ├── knowledge.js    # 知识库
│   ├── settings.js     # 系统设置
│   ├── costs.js        # 成本看板
│   ├── tasks.js        # 任务队列
│   ├── aiChat.js       # AI 对话记录
│   ├── apiKeys.js      # API 密钥
│   ├── users.js        # 用户管理
│   ├── audit.js        # 操作审计
│   └── screen.js       # 数据大屏
└── README.md
```

## 迁移约定

1. **key 前缀**：业务文案一律加自己域名的前缀（`settings.*`、`recordings.*`），
   不与其它域共享 key；通用词优先复用 `base.js` 的 `common.*` / `a11y.*` / `time.*`。
2. **zh / en 同步**：每个模块的 `zh` 与 `en` key 必须一一对应；中文文案保持原文
   （部分单测断言中文输出），英文用简洁、面向操作台的 UI 英文。
3. **动态文案用插值**：`t('recordings.uploadedCount', { count: n })`，
   不要把翻译片段字符串拼接。
4. **必须翻译**：模板文本、`label` / `placeholder` / `title` / `aria-label`、
   `ElMessage` / `ElMessageBox` / `ElNotification`、表单校验消息、空态文案、选项 label。
5. **不要翻译**：代码注释（可继续用中文）、后端返回的数据、枚举 value、URL、
   文件名 / 扩展名、品牌名。
6. **枚举标签**：`src/constants.js` 里的 `STATUS_META`、`ROLE_META`、`PRIORITY_META`、
   `AUDIT_ACTION_META` 等对象的 `label` 已是 getter，读取时自动按语言翻译；
   选项数组的 `label` 同样是 getter。页面里直接使用这些对象 / helper 即可，
   不要在视图里硬编码状态中文，也不要修改 `constants.js` 里的 key（在 `meta.js` 维护）。
7. **持久化数据值**：如果中文值会写进后端（例如知识库分类候选），保留原值，
   仅在展示层做「值 → 文案」映射；确需保留的中文字面量在该行加
   `// i18n-audit-ignore` 显式豁免审计。
8. **Import 路径**：Vue 组件 `from '../i18n'`；`.js` 工具 / 常量 `from '../i18n/index.js'`
   （Node ESM 不支持目录导入，`npm test` 会直接报错）。

## 校验

```bash
cd frontend
npm run i18n:audit                      # = node scripts/i18n-audit.mjs
node scripts/i18n-audit.mjs views/Review.vue   # 只扫描指定文件（路径片段过滤）
node --test tests/i18n.test.mjs         # key 对齐 / 回退 / 插值 / 持久化
npm test                                # 全量单测
npm run build                           # Vite 构建（SFC 编译校验）
```

`i18n-audit` 会忽略 `src/i18n/**` 词典与注释；行内 `i18n-audit-ignore` 用于
确实不可翻译的中文字面量（如持久化数据值）。

## 覆盖范围

- 已迁移：页头、侧栏导航、登录页、路由标题、数据大屏、**全部内容页**（工作台 / 录音 /
  复检 / 报表 / 敏感词库 / 语料库 / 知识库 / 设置 / 成本 / 任务 / AI 对话 / API 密钥 /
  用户 / 审计）及其共享组件与工具。
- 后端返回的中文提示（校验错误、运营通知内容等）属于服务端数据，不在前端词典范围。
