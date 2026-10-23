import { createApiClient, createBlogApi } from '@simple-notes/api'

/** 博客前台只使用公开接口（匿名可读），不注入 Bearer */
export const blogApi = createBlogApi(createApiClient())
