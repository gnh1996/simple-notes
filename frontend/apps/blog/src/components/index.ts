/**
 * 组件词汇表（docs/blog-runtime-theme.md §3.4）：只增不改。
 *
 * 组织方式：一个组件一个文件，本文件只保留注册表。
 * - 组件一律是 `.vue` 单文件组件（模板由构建期编译，SCSS 无、`<style>` 禁止）；
 * - 文件名 = 组件名去掉 `blog-` 前缀；
 * - `scripts/theme-contract.mjs` 按此处的导入定位每个组件文件，用 `vue/compiler-sfc`
 *   读出 `<template>` 逐个核对 class / slot 与 `contract.ts` 的登记；
 * - 注册表的值必须是组件标识符（自检按标识符定位文件），不要写成
 *   `() => import(...)` 之类的动态形式。
 */

import type { Component } from 'vue'

import BlogArchives from './archives.vue'
import BlogBackToTop from './back-to-top.vue'
import BlogImage from './image.vue'
import BlogMarkdown from './markdown.vue'
import BlogPager from './pager.vue'
import BlogPostCard from './post-card.vue'
import BlogReadingProgress from './reading-progress.vue'
import BlogSearchBox from './search-box.vue'
import BlogState from './state.vue'
import BlogTagChip from './tag-chip.vue'
import BlogTagCloud from './tag-cloud.vue'
import BlogThemeToggle from './theme-toggle.vue'
import BlogToc from './toc.vue'

export const blogComponents: Record<string, Component> = {
  'blog-post-card': BlogPostCard,
  'blog-pager': BlogPager,
  'blog-markdown': BlogMarkdown,
  'blog-toc': BlogToc,
  'blog-tag-chip': BlogTagChip,
  'blog-tag-cloud': BlogTagCloud,
  'blog-archives': BlogArchives,
  'blog-search-box': BlogSearchBox,
  'blog-theme-toggle': BlogThemeToggle,
  'blog-image': BlogImage,
  'blog-back-to-top': BlogBackToTop,
  'blog-reading-progress': BlogReadingProgress,
  'blog-state': BlogState,
}
