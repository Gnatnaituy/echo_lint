import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import eslintConfigPrettier from 'eslint-config-prettier'
import globals from 'globals'

/**
 * ESLint 9 flat config（审计 P2-29）。
 *
 * 规则集：eslint:recommended + eslint-plugin-vue flat/recommended（Vue 3 SFC）。
 * 格式类规则交给 Prettier —— 末尾的 eslint-config-prettier 会关闭与 Prettier 冲突的规则，
 * 保证 `npm run lint`（正确性）与 `npm run format:check`（格式）职责分离。
 *
 * 注意：接入初期不要对存量源码执行 `eslint --fix` / `prettier --write` 全量重排；
 * 由维护者在功能改动完成后统一执行 `npm run format` 收敛格式。
 */
export default [
  {
    // 构建产物、依赖与生成文件不参与检查
    ignores: ['dist/**', 'node_modules/**', 'coverage/**', 'public/**', '*.min.js']
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.{js,mjs,cjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node
      }
    },
    rules: {
      // 组件名多为业务单词（App.vue / Review.vue 等），不强制多单词命名
      'vue/multi-word-component-names': 'off',
      // 未使用变量先降级为 warning：存量代码逐步清理，不阻塞 CI
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      // 字符类中为可读性保留的转义（如 [【\[]）不影响语义，接入期先告警
      'no-useless-escape': 'warn',
      // 项目在开发/构建脚本中有意使用 console，交由 Prettier 之外的规则集中管理
      'no-console': 'off'
    }
  },
  {
    // 测试脚本运行在 Node 环境（node:test）
    files: ['tests/**/*.mjs', 'scripts/**/*.mjs'],
    languageOptions: {
      globals: { ...globals.node }
    }
  },
  eslintConfigPrettier
]
