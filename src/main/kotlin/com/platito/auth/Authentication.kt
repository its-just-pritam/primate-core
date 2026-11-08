package com.platito.auth

import com.platito.common.unauthorized
import com.platito.user.UserRecord
import com.platito.user.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.HandlerMethod
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Marks a handler that does not require a session token. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Public

private const val USER_ATTRIBUTE = "com.platito.auth.user"

/** Requires `Authorization: Bearer <session token>` on every handler not marked [Public]. */
@Component
class AuthInterceptor(
    private val verifier: SessionTokenVerifier,
    private val users: UserRepository,
) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (handler !is HandlerMethod || handler.hasMethodAnnotation(Public::class.java)) return true
        val token = request.getHeader("Authorization")
            ?.let { Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val claims = verifier.verify(token) ?: throw unauthorized()
        val user = users.findByEmail(claims.username) ?: throw unauthorized()
        request.setAttribute(USER_ATTRIBUTE, user)
        return true
    }
}

/** Injects the authenticated [UserRecord] into controller parameters. */
@Component
class CurrentUserResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter) = parameter.parameterType == UserRecord::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any = webRequest.getAttribute(USER_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST) ?: throw unauthorized()
}

@Configuration
class WebConfig(
    private val authInterceptor: AuthInterceptor,
    private val currentUserResolver: CurrentUserResolver,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**")
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentUserResolver)
    }
}
