# i18n（中 / 英）

轻量、零依赖的国际化模块（Batch 4b 引入）。

## 用法

```js
import { t, setLocale, useI18n } from '../i18n'

t('nav.dashboard')                 // 当前语言 → 中文回退 → key 本身
t('login.welcome', { name: '张三' }) // 支持 {param} 插值
setLocale('en')                    // 切换并持久化到 localStorage（echolint.locale）
const { locale, t } = useI18n()    // 组件内组合式入口；locale 为 computed
```

- 词典：`zh.js`（默认 / 回退语言）与 `en.js`，两者 key 必须完全对齐（`tests/i18n.test.mjs` 校验）。
- 缺失 key：英文缺失自动回退中文；中文也缺失时原样返回 key，便于发现漏翻译。
- `setLocale` 会同步 `<html lang>`（zh → `zh-CN`，en → `en`），并在浏览器中用
  localStorage 记住选择；隐私模式下写入失败会自动忽略。

## 当前覆盖范围

已迁移（页头 / 侧栏 / 登录页 / 路由标题 / 通用按钮 / 数据大屏）：

- 页头：面包屑、服务状态、待复检、账号菜单、修改密码弹窗、语言切换
- 侧栏：分组标题、全部导航项、流水线提示、折叠按钮 aria-label
- 登录页：标题 / 副标题 / 表单标签与占位 / 按钮 / 页脚 / 错误信息
- 路由标题：`router` 的 `meta.titleKey`（document.title 跟随语言）
- 数据大屏 `/screen`：全部标签、退出、自动刷新说明
- 通用按钮：保存 / 取消 / 删除 / 确认 / 关闭 / 刷新 / 重试 / 搜索 / 重置 / 复制
  （已在页头密码弹窗、数据大屏等共用场景使用）

其它业务页面（录音、复核、报表、设置等）文案仍为中文，属于**增量迁移**范围：
新增文案时在 `zh.js` / `en.js` 同步添加 key，再把模板中的硬编码中文替换为 `t(key)` 即可。

## 新增文案清单

1. 在 `zh.js` 与 `en.js` 同时添加同名 key（key 用点分层级，如 `nav.dashboard`）。
2. 需要插值时使用 `{name}` 形式。
3. 跑 `npm test`，`tests/i18n.test.mjs` 会校验 key 对齐 / 回退 / 持久化。
