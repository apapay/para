/*
 * Copyright 2013-2016 Erudika. https://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.security;

import com.erudika.para.rest.RestUtils;
import com.erudika.para.utils.Config;
import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

/**
 * Simple handler for successful authentication requests.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class SimpleAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

	private RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException, ServletException {

		String customURI = (String) request.getAttribute(Config.AUTH_SIGNIN_FAILURE_ATTR);
		if (customURI == null && request.getParameter("appid") != null) {
			// try to reload custom redirect URI from app
			SecurityUtils.getCustomAuthSettings(request.getParameter("appid"), null, request);
			customURI = (String) request.getAttribute(Config.AUTH_SIGNIN_FAILURE_ATTR);
		}
		if (!StringUtils.isBlank(customURI)) {
			redirectStrategy.sendRedirect(request, response, customURI);
		} else {
			if (isRestRequest(request)) {
				RestUtils.returnStatusResponse(response, HttpServletResponse.SC_UNAUTHORIZED, exception.getMessage());
			} else {
				super.onAuthenticationFailure(request, response, exception);
			}
		}
	}

	/**
	 * Checks if it is a rest request.
	 * @param request the request
	 * @return true if rest or ajax
	 */
	protected boolean isRestRequest(HttpServletRequest request) {
		return RestRequestMatcher.INSTANCE.matches(request) || AjaxRequestMatcher.INSTANCE.matches(request);
	}
}
