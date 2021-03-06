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

import com.eaio.uuid.UUID;
import com.erudika.para.core.utils.ParaObjectUtils;
import com.erudika.para.core.User;
import com.erudika.para.utils.Config;
import com.erudika.para.utils.Utils;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.ParseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;

/**
 *
 * @author Alex Bogdanovski [alex@erudika.com]
 */
public class TwitterAuthFilter extends AbstractAuthenticationProcessingFilter {

	private final CloseableHttpClient httpclient;
	private final ObjectReader jreader;
	private static final String FLOW_URL1 = "https://api.twitter.com/oauth/request_token";
	private static final String FLOW_URL2 = "https://api.twitter.com/oauth/authenticate?";
	private static final String FLOW_URL3 = "https://api.twitter.com/oauth/access_token";
	private static final String PROFILE_URL = "https://api.twitter.com/1.1/account/verify_credentials.json";

	/**
	 * The default filter mapping.
	 */
	public static final String TWITTER_ACTION = "twitter_auth";

	/**
	 * Default constructor.
	 * @param defaultFilterProcessesUrl the url of the filter
	 */
	public TwitterAuthFilter(final String defaultFilterProcessesUrl) {
		super(defaultFilterProcessesUrl);
		this.jreader = ParaObjectUtils.getJsonReader(Map.class);
		this.httpclient = HttpClients.createDefault();
	}

	/**
	 * Handles an authentication request.
	 * @param request HTTP request
	 * @param response HTTP response
	 * @return an authentication object that contains the principal object if successful.
	 * @throws IOException ex
	 */
	@Override
	public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
			throws IOException {
		final String requestURI = request.getRequestURI();
		UserAuthentication userAuth = null;

		if (requestURI.endsWith(TWITTER_ACTION)) {
			String verifier = request.getParameter("oauth_verifier");
			String appid = request.getParameter("appid");
			String denied = request.getParameter("denied");
			String redirectURI = request.getRequestURL().toString() + (appid == null ? "" : "?appid=" + appid);
			String[] keys = SecurityUtils.getCustomAuthSettings(appid, Config.TWITTER_PREFIX, request);

			if (denied != null) {
				throw new BadCredentialsException("Cancelled.");
			}

			if (verifier == null && stepOne(response, redirectURI, keys)) {
				return null;
			} else {
				userAuth = stepTwo(request, verifier, keys, appid);
			}
		}

		User user = SecurityUtils.getAuthenticatedUser(userAuth);

		if (userAuth == null || user == null || user.getIdentifier() == null) {
			throw new BadCredentialsException("Bad credentials.");
		} else if (!user.getActive()) {
			throw new LockedException("Account is locked.");
		}
		return userAuth;
	}

	private boolean stepOne(HttpServletResponse response, String redirectURI, String[] keys)
			throws ParseException, IOException {
		String callback = Utils.urlEncode(redirectURI);
		Map<String, String[]> params = new HashMap<String, String[]>();
		params.put("oauth_callback", new String[]{callback});
		HttpPost tokenPost = new HttpPost(FLOW_URL1);
		tokenPost.setHeader(HttpHeaders.AUTHORIZATION, OAuth1HmacSigner.sign("POST", FLOW_URL1,
				params, keys[0], keys[1], null, null));
		tokenPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
		CloseableHttpResponse resp1 = httpclient.execute(tokenPost);

		if (resp1.getStatusLine().getStatusCode() == HttpServletResponse.SC_OK) {
			String decoded = EntityUtils.toString(resp1.getEntity());
			for (String pair : decoded.split("&")) {
				if (pair.startsWith("oauth_token")) {
					response.sendRedirect(FLOW_URL2 + pair);
					return true;
				}
			}
		}
		EntityUtils.consumeQuietly(resp1.getEntity());
		return false;
	}

	private UserAuthentication stepTwo(HttpServletRequest request, String verifier, String[] keys, String appid)
			throws ParseException, UnsupportedEncodingException, IOException {
		String token = request.getParameter("oauth_token");
		Map<String, String[]> params = new HashMap<String, String[]>();
		params.put("oauth_verifier", new String[]{verifier});
		HttpPost tokenPost = new HttpPost(FLOW_URL3);
		tokenPost.setEntity(new StringEntity("oauth_verifier=" + verifier));
		tokenPost.setHeader(HttpHeaders.AUTHORIZATION, OAuth1HmacSigner.sign("POST", FLOW_URL3,
				params, keys[0], keys[1], token, null));
		tokenPost.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
		CloseableHttpResponse resp2 = httpclient.execute(tokenPost);

		if (resp2.getStatusLine().getStatusCode() == HttpServletResponse.SC_OK) {
			String decoded = EntityUtils.toString(resp2.getEntity());
			String oauthToken = null;
			String oauthSecret = null;
			for (String pair : decoded.split("&")) {
				if (pair.startsWith("oauth_token_secret")) {
					oauthSecret = pair.substring(19);
				} else if (pair.startsWith("oauth_token")) {
					oauthToken = pair.substring(12);
				}
			}
			EntityUtils.consumeQuietly(resp2.getEntity());
			return getOrCreateUser(appid, oauthToken + Config.SEPARATOR + oauthSecret);
		}
		return null;
	}

	/**
	 * Calls the Twitter API to get the user profile using a given access token.
	 * @param appid app identifier of the parent app, use null for root app
	 * @param accessToken token in the format "oauth_token:oauth_secret"
	 * @return {@link UserAuthentication} object or null if something went wrong
	 * @throws IOException ex
	 */
	public UserAuthentication getOrCreateUser(String appid, String accessToken) throws IOException {
		UserAuthentication userAuth = null;
		if (accessToken != null && accessToken.contains(Config.SEPARATOR)) {
			String[] tokens = accessToken.split(Config.SEPARATOR);
			String[] keys = SecurityUtils.getCustomAuthSettings(appid, Config.TWITTER_PREFIX, null);
			User user = new User();
			user.setAppid(appid);

			Map<String, String[]> params2 = new HashMap<String, String[]>();
			HttpGet profileGet = new HttpGet(PROFILE_URL + "?include_email=true");
			params2.put("include_email", new String[]{"true"});
			profileGet.setHeader(HttpHeaders.AUTHORIZATION, OAuth1HmacSigner.sign("GET", PROFILE_URL,
					params2, keys[0], keys[1], tokens[0], tokens[1]));
			CloseableHttpResponse resp3 = httpclient.execute(profileGet);

			if (resp3.getStatusLine().getStatusCode() == HttpServletResponse.SC_OK) {
				Map<String, Object> profile = jreader.readValue(resp3.getEntity().getContent());

				if (profile != null && profile.containsKey("id_str")) {
					String twitterId = (String) profile.get("id_str");
					String pic = (String) profile.get("profile_image_url_https");
					String alias = (String) profile.get("screen_name");
					String name = (String) profile.get("name");
					String email = (String) profile.get("email");

					user.setIdentifier(Config.TWITTER_PREFIX + twitterId);
					user = User.readUserForIdentifier(user);
					if (user == null) {
						//user is new
						user = new User();
						user.setActive(true);
						user.setAppid(appid);
						user.setEmail(StringUtils.isBlank(email) ? alias + "@twitter.com" : email);
						user.setName(StringUtils.isBlank(name) ? "No Name" : name);
						user.setPassword(new UUID().toString());
						user.setPicture(getPicture(pic));
						user.setIdentifier(Config.TWITTER_PREFIX + twitterId);
						String id = user.create();
						if (id == null) {
							throw new AuthenticationServiceException("Authentication failed: cannot create new user.");
						}
					} else {
						String picture = getPicture(pic);
						boolean update = false;
						if (!StringUtils.equals(user.getPicture(), picture)) {
							user.setPicture(picture);
							update = true;
						}
						if (!StringUtils.isBlank(email) && !StringUtils.equals(user.getEmail(), email)) {
							user.setEmail(email);
							update = true;
						}
						if (update) {
							user.update();
						}
					}
					userAuth = new UserAuthentication(new AuthenticatedUserDetails(user));
				}
				EntityUtils.consumeQuietly(resp3.getEntity());
			}
		}
		return userAuth;
	}

	private static String getPicture(String pic) {
		if (pic != null) {
			pic = pic.replace("_normal", "");
			if (pic.indexOf("?") > 0) {
				// user picture migth contain size parameters - remove them
				return pic.substring(0, pic.indexOf("?"));
			} else {
				return pic;
			}
		}
		return null;
	}
}
