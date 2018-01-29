/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License
 */

package org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt;

import com.nimbusds.jwt.ReadOnlyJWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.base.IdentityConstants;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.common.OAuth2ErrorCodes;
import org.wso2.carbon.identity.oauth2.bean.OAuthClientAuthnContext;
import org.wso2.carbon.identity.oauth2.client.authentication.AbstractOAuthClientAuthenticator;
import org.wso2.carbon.identity.oauth2.client.authentication.OAuthClientAuthnException;
import org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.validator.JWTValidator;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;

import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.DEFAULT_ENABLE_JTI_CACHE;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.DEFAULT_VALIDITY_PERIOD_IN_MINUTES;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.ISSUER;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.OAUTH_JWT_ASSERTION;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.OAUTH_JWT_ASSERTION_TYPE;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.OAUTH_JWT_BEARER_GRANT_TYPE;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.PREVENT_TOKEN_REUSE;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.REJECT_BEFORE_IN_MINUTES;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.SIGNED_JWT;
import static org.wso2.carbon.identity.oauth2.token.handler.clientauth.jwt.Constants.TOKEN_ENDPOINT_ALIAS;

/**
 * Client Authentication handler to implement oidc private_key_jwt client authentication spec
 * http://openid.net/specs/openid-connect-core-1_0.html#ClientAuthentication.
 */
public class PrivateKeyJWTClientAuthenticator extends AbstractOAuthClientAuthenticator {

    private static final Log log = LogFactory.getLog(PrivateKeyJWTClientAuthenticator.class);
    private JWTValidator jwtValidator;

    public PrivateKeyJWTClientAuthenticator() {

        String tokenEPAlias = properties.getProperty(TOKEN_ENDPOINT_ALIAS);
        String issuer = properties.getProperty(ISSUER);
        boolean preventTokenReuse = Boolean.parseBoolean(properties.getProperty(PREVENT_TOKEN_REUSE));
        int rejectBeforePeriod = DEFAULT_VALIDITY_PERIOD_IN_MINUTES;
        if (properties.getProperty(REJECT_BEFORE_IN_MINUTES) != null) {
            rejectBeforePeriod = Integer.parseInt(properties.getProperty(REJECT_BEFORE_IN_MINUTES));
        }
        jwtValidator = createJWTValidator(tokenEPAlias, issuer, preventTokenReuse, rejectBeforePeriod);
    }

    /**
     * To check whether the authentication is successful.
     *
     * @param httpServletRequest      http servelet request
     * @param bodyParameters          map of request body params
     * @param oAuthClientAuthnContext oAuthClientAuthnContext
     * @return true if the authentication is successful.
     * @throws OAuthClientAuthnException
     */
    @Override
    public boolean authenticateClient(HttpServletRequest httpServletRequest, Map<String, List> bodyParameters,
                                      OAuthClientAuthnContext oAuthClientAuthnContext) throws OAuthClientAuthnException {

        String oauthJWTAssertion = getBodyParameters(bodyParameters).get(OAUTH_JWT_ASSERTION);
        Object signedJWT = oAuthClientAuthnContext.getParameter(SIGNED_JWT);
        if (signedJWT != null) {
            return jwtValidator.isValidAssertion((SignedJWT) signedJWT);
        }
        return jwtValidator.isValidAssertion(getSignedJWT(oauthJWTAssertion));
    }

    /**
     * Returns whether the incoming request can be handled by the particular authenticator.
     *
     * @param httpServletRequest      http servelet request
     * @param bodyParameters          map of request body params
     * @param oAuthClientAuthnContext oAuthClientAuthnContext
     * @return true if the incoming request can be handled.
     */
    @Override
    public boolean canAuthenticate(HttpServletRequest httpServletRequest, Map<String, List> bodyParameters,
                                   OAuthClientAuthnContext oAuthClientAuthnContext) {

        String oauthJWTAssertionType = getBodyParameters(bodyParameters).get(OAUTH_JWT_ASSERTION_TYPE);
        String oauthJWTAssertion = getBodyParameters(bodyParameters).get(OAUTH_JWT_ASSERTION);
        return isValidJWTClientAssertionRequest(oauthJWTAssertionType, oauthJWTAssertion);
    }

    /**
     * Retrievs the client ID which is extracted from the JWT.
     *
     * @param httpServletRequest
     * @param bodyParameters
     * @param oAuthClientAuthnContext
     * @return jwt 'sub' value as the client id
     * @throws OAuthClientAuthnException
     */
    @Override
    public String getClientId(HttpServletRequest httpServletRequest, Map<String, List> bodyParameters,
                              OAuthClientAuthnContext oAuthClientAuthnContext) throws OAuthClientAuthnException {

        String oauthJWTAssertion = getBodyParameters(bodyParameters).get(OAUTH_JWT_ASSERTION);
        SignedJWT signedJWT = getSignedJWT(oauthJWTAssertion);
        oAuthClientAuthnContext.addParameter(SIGNED_JWT, signedJWT);
        ReadOnlyJWTClaimsSet claimsSet = jwtValidator.getClaimSet(signedJWT);
        return jwtValidator.resolveSubject(claimsSet);
    }

    private SignedJWT getSignedJWT(String assertion) throws OAuthClientAuthnException {

        SignedJWT signedJWT;
        if (isEmpty(assertion)) {
            String errorMessage = "No Valid Assertion was found for " + Constants.OAUTH_JWT_BEARER_GRANT_TYPE;
            throw new OAuthClientAuthnException(errorMessage, OAuth2ErrorCodes.INVALID_REQUEST);
        }
        try {
            signedJWT = SignedJWT.parse(assertion);
            logJWT(signedJWT);
        } catch (ParseException e) {
            String errorMessage = "Error while parsing the JWT.";
            throw new OAuthClientAuthnException(errorMessage, OAuth2ErrorCodes.INVALID_REQUEST);
        }
        if (signedJWT == null) {
            String errorMessage = "No Valid Assertion was found for " + OAUTH_JWT_BEARER_GRANT_TYPE;
            throw new OAuthClientAuthnException(errorMessage, OAuth2ErrorCodes.INVALID_REQUEST);
        }
        return signedJWT;
    }

    private void logJWT(SignedJWT signedJWT) {

        if (log.isDebugEnabled() && IdentityUtil.isTokenLoggable(IdentityConstants.IdentityTokens.ACCESS_TOKEN)) {
            log.debug(signedJWT);
        }
    }

    private boolean isValidJWTClientAssertionRequest(String clientAssertionType, String clientAssertion) {

        if (log.isDebugEnabled()) {
            log.debug("Authenticate Requested with : " + clientAssertionType + ".");
            if (IdentityUtil.isTokenLoggable(IdentityConstants.IdentityTokens.ACCESS_TOKEN)) {
                log.debug(clientAssertion);
            }
        }
        return OAUTH_JWT_BEARER_GRANT_TYPE.equals(clientAssertionType) && isNotEmpty(clientAssertion);
    }

    private JWTValidator createJWTValidator(String tokenEPAlias, String issuer, boolean preventTokenReuse,
                                            int rejectBefore) {

        List<Object> mandatoryClaims = new ArrayList<>();
        boolean enableJTICache = DEFAULT_ENABLE_JTI_CACHE;
        return new JWTValidator(preventTokenReuse, tokenEPAlias, rejectBefore, issuer, mandatoryClaims, enableJTICache);
    }

}