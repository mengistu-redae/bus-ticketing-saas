import { createContext, useContext, useMemo } from 'react';
import { useAuthMe } from '../api/queries.js';

const AuthContext = createContext(null);

/**
 * Wraps GET /auth/me. Role info comes from req.session.user, which is the
 * OIDC ID token's claims (see node-bff/src/routes/auth.js) - whether
 * realm_access.roles actually lands there depends on Keycloak's "roles"
 * client scope mapper config, unconfirmed against a real token as of
 * writing (see the frontend build plan's Phase 0 note). hasRole() is
 * written defensively either way: no roles claim just means every
 * hasRole() check returns false, degrading to "show nothing role-gated"
 * rather than throwing.
 */
export function AuthProvider({ children }) {
  const { data, isLoading } = useAuthMe();

  const value = useMemo(() => {
    const authenticated = Boolean(data?.authenticated);
    const user = authenticated ? data.user : null;
    const roles = (user?.realm_access?.roles || []).map((r) => r.toLowerCase());
    return {
      isLoading,
      authenticated,
      user,
      roles,
      hasRole: (role) => roles.includes(role.toLowerCase()),
    };
  }, [data, isLoading]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return ctx;
}
