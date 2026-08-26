'use strict';

const { Issuer } = require('openid-client');

const DISCOVERY_RETRY_DELAY_MS = 3000;
// ~1 minute of retries - Keycloak's `start-dev --import-realm` is slow on a
// cold start, and docker-compose's depends_on only waits for the container
// to start, not for its HTTP listener + realm import to actually finish.
const DISCOVERY_MAX_ATTEMPTS = 20;

/**
 * Discovers Keycloak's OIDC metadata and builds a confidential client.
 *
 * KEYCLOAK_ISSUER vs KEYCLOAK_ISSUER_PUBLIC: Keycloak runs with
 * KC_HOSTNAME_STRICT=false, so it reports whatever host a request came in
 * on. This discovery call is made from inside the docker network, so it
 * gets back "http://keycloak:8080/..." endpoints - correct for the calls
 * *we* make server-to-server (token, jwks, userinfo), but wrong for
 * authorization_endpoint and end_session_endpoint, which are redirect
 * targets we hand to the user's browser. The browser can't resolve
 * "keycloak" - only KEYCLOAK_ISSUER_PUBLIC's host (localhost:8080, published
 * by docker-compose), so those two get rewritten below.
 *
 * The token endpoint is the one that actually mints tokens, so the `iss`
 * claim inside them reflects the internal host regardless of which host the
 * browser used to reach the authorization endpoint - that's why
 * issuer.metadata.issuer itself is left as the internal value returned by
 * discovery, not rewritten.
 */
async function buildOidcClient() {
  const internalIssuer = await discoverWithRetry();

  const internalOrigin = new URL(process.env.KEYCLOAK_ISSUER).origin;
  const publicOrigin = new URL(process.env.KEYCLOAK_ISSUER_PUBLIC).origin;
  const toPublic = (url) => (url ? url.replace(internalOrigin, publicOrigin) : url);

  const issuer = new Issuer({
    ...internalIssuer.metadata,
    authorization_endpoint: toPublic(internalIssuer.metadata.authorization_endpoint),
    end_session_endpoint: toPublic(internalIssuer.metadata.end_session_endpoint),
  });

  return new issuer.Client({
    client_id: process.env.KEYCLOAK_CLIENT_ID,
    client_secret: process.env.KEYCLOAK_CLIENT_SECRET,
    redirect_uris: [`${process.env.BFF_BASE_URL}/auth/callback`],
    response_types: ['code'],
  });
}

async function discoverWithRetry() {
  for (let attempt = 1; attempt <= DISCOVERY_MAX_ATTEMPTS; attempt++) {
    try {
      return await Issuer.discover(process.env.KEYCLOAK_ISSUER);
    } catch (err) {
      if (attempt === DISCOVERY_MAX_ATTEMPTS) {
        throw err;
      }
      console.warn(
        `[oidc] discovery attempt ${attempt}/${DISCOVERY_MAX_ATTEMPTS} failed (${err.message}), ` +
        `retrying in ${DISCOVERY_RETRY_DELAY_MS}ms...`
      );
      await new Promise((resolve) => setTimeout(resolve, DISCOVERY_RETRY_DELAY_MS));
    }
  }
  // Unreachable - the loop above always either returns or throws.
  throw new Error('OIDC discovery exhausted its retries');
}

module.exports = { buildOidcClient };
