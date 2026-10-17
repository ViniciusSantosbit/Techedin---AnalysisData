package com.techedin.backend.account;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

/** Constructed inside the security chain, after its session context is loaded. */
public class AccountSessionGuard extends OncePerRequestFilter {
    private final AccountService accounts;
    private final boolean required;
    public AccountSessionGuard(AccountService accounts,boolean required) { this.accounts=accounts; this.required=required; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if (authentication!=null && authentication.getPrincipal() instanceof AccountPrincipal user) {
            boolean valid;
            try { valid=accounts.sessionValid(user,required); }
            catch (org.springframework.dao.DataAccessException error) {
                response.setStatus(503); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"Conta temporariamente indisponível. Tente novamente.\"}"); return;
            }
            if (!valid) new SecurityContextLogoutHandler().logout(request,response,authentication);
        }
        chain.doFilter(request,response);
    }
}
