package cc.naihao.simplenotes.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * blog runtime 静态资源缓存头（docs/blog-runtime-theme.md §5.3）。
 *
 * <p>产物在 jar 内 {@code static/blog/}：入口 {@code runtime.js} 名称固定，必须重校验；
 * Vite 输出的 chunk / 静态资源带内容哈希，可长缓存。
 */
@Component
public class BlogAssetCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/blog/runtime.js".equals(path)) {
            response.setHeader("Cache-Control", "no-cache");
        } else if (path.startsWith("/blog/chunks/") || path.startsWith("/blog/assets/")) {
            response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        }
        filterChain.doFilter(request, response);
    }
}
