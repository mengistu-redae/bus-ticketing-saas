'use strict';

const express = require('express');
const { TokenSet } = require('openid-client');

// Paths that are permitAll()'d in spring-boot-api's SecurityConfig and so
// must reach it with no session required here either - mounted ahead of
// requireSession/refreshIfExpired below, so each terminates the router
// before either middleware runs. forwardToApi already only attaches an
// Authorization header when a session exists, so an authenticated
// customer/agent hitting these same paths (e.g. a logged-in customer
// searching trips) still gets their Bearer token forwarded exactly as
// today - this list only ever *adds* anonymous access, it doesn't change
// authenticated behavior.
//
// Literal paths (/trips/search, /trips/locations) are listed before the
// /trips/:tripId param route so Express's registration-order route
// matching doesn't let the param route swallow them.
const PUBLIC_ROUTES = [
  ['get', '/cargo/track/*'], // consignor/consignee tracking - see CargoWaybillController.track
  ['get', '/trips/search'],
  ['get', '/trips/locations'],
  ['get', '/trips/:tripId/seats'],
  ['get', '/trips/:tripId'],
  ['post', '/bookings/guest'], // guest (no-account) booking creation - see BookingController.createGuestBooking
  ['get', '/bookings/guest/track/:bookingRef'], // guest booking lookup - see BookingController.trackGuestBooking
];

/**
 * Everything under /api is forwarded to the Spring Boot API with the
 * session's access token attached as a Bearer header - the browser never
 * holds or sends a token itself, only this BFF's session cookie. Spring
 * Security on the other side re-validates that token and re-derives
 * TenantContext from it exactly as if the caller had sent it directly.
 */
function buildApiRouter(getClient) {
  const router = express.Router();

  for (const [method, path] of PUBLIC_ROUTES) {
    router[method](path, forwardToApi);
  }

  router.use(requireSession);
  router.use(refreshIfExpired(getClient));
  router.all('*', forwardToApi);

  return router;
}

function requireSession(req, res, next) {
  if (!req.session.tokenSet) {
    return res.status(401).json({ error: 'Not authenticated' });
  }
  next();
}

/**
 * Refreshes the access token before forwarding if it's expired, so the
 * browser never has to know or care about token lifetimes - that's the
 * point of holding tokens server-side instead of in the browser.
 */
function refreshIfExpired(getClient) {
  return async (req, res, next) => {
    try {
      // req.session.tokenSet round-trips through Redis as plain JSON between
      // requests (connect-redis stores the session with JSON.stringify), so
      // it's a plain object here, not the TokenSet instance auth.js's
      // /callback originally stored - it has the same fields but none of
      // TokenSet's methods. Rewrap it before calling .expired().
      let tokenSet = new TokenSet(req.session.tokenSet);
      if (tokenSet.expired() && tokenSet.refresh_token) {
        tokenSet = await getClient().refresh(tokenSet.refresh_token);
        req.session.tokenSet = tokenSet;
      }
      next();
    } catch (err) {
      // Found live: a browser tab left open long enough for Keycloak's own
      // refresh-token lifetime (not just the access token's) to lapse hits
      // this, not the `if` above - the access token look expired so a
      // refresh was attempted, but the refresh token itself is also no
      // longer valid. openid-client surfaces that as an OPError
      // ("invalid_grant" / "Token is not active"), which used to fall
      // through to next(err) and the generic 500 handler - a genuinely
      // unrecoverable session (the user must log in again) was showing up
      // to the frontend as an opaque "Internal BFF error" instead of the
      // same 401 shape requireSession already returns for "no session at
      // all", which the frontend already knows how to turn into a login
      // redirect. Destroy the now-useless session so it doesn't keep
      // failing the same way on every subsequent request either.
      if (err.name === 'OPError' && err.error === 'invalid_grant') {
        return req.session.destroy(() => {
          res.status(401).json({ error: 'Session expired' });
        });
      }
      next(err);
    }
  };
}

/**
 * express.json() sets req.body to {} even for a request with no body and no
 * Content-Type header at all (e.g. `POST .../cancel` with nothing to send) -
 * checking `body !== undefined` alone doesn't distinguish that from a real
 * JSON body. That mattered more than it looks: forwarding {} with no
 * Content-Type left Node's fetch() defaulting to
 * "text/plain;charset=UTF-8", and spring-boot-api's
 * @RequestBody(required = false) parameters on endpoints like
 * CancellationController.cancelMyBooking - which never even reference the
 * body in their @PreAuthorize check - still somehow surfaced that as a 403
 * "insufficient_scope" rather than a 415, not the plain success a genuinely
 * missing body should get. Confirmed by curl reproducing the exact
 * byte-for-byte request both ways directly against spring-boot-api. Real
 * fix: forward a body only when one actually has content, always with the
 * right Content-Type when it does - see forwardToApi below.
 */
function shouldForwardBody(method, body) {
  return !['GET', 'HEAD'].includes(method) && body !== undefined && Object.keys(body).length > 0;
}

async function forwardToApi(req, res, next) {
  try {
    const targetUrl = `${process.env.API_BASE_URL}${req.originalUrl}`;

    const headers = { ...req.headers };
    delete headers.host; // let fetch set the right Host for API_BASE_URL
    delete headers.cookie; // this BFF's session cookie is ours, not the API's
    delete headers['content-length']; // body is about to be re-serialized below, length would be stale
    delete headers['content-type']; // set explicitly below, only when there's an actual body - see shouldForwardBody's comment
    // Only a PUBLIC_ROUTES path (mounted ahead of requireSession above) can
    // reach here with no session at all - spring-boot-api's permitAll() on
    // each of those routes doesn't need a Bearer token either.
    if (req.session?.tokenSet?.access_token) {
      headers.authorization = `Bearer ${req.session.tokenSet.access_token}`;
    }

    const hasBody = shouldForwardBody(req.method, req.body);
    if (hasBody) {
      headers['content-type'] = 'application/json';
    }

    const upstream = await fetch(targetUrl, {
      method: req.method,
      headers,
      body: hasBody ? JSON.stringify(req.body) : undefined,
    });

    res.status(upstream.status);
    upstream.headers.forEach((value, key) => {
      if (key.toLowerCase() !== 'transfer-encoding') {
        res.setHeader(key, value);
      }
    });
    res.send(Buffer.from(await upstream.arrayBuffer()));
  } catch (err) {
    next(err);
  }
}

module.exports = { buildApiRouter, requireSession, refreshIfExpired, shouldForwardBody };
