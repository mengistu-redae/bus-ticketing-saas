import { createContext, useContext, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiGet } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { themeVars } from '../lib/color.js';

const BrandingContext = createContext(null);

const VARS = [
  '--brand', '--brand-dark', '--brand-light',
  '--accent', '--accent-dark', '--accent-light',
];

/**
 * Themes the staff workspace (operator_admin / agent) with the signed-in
 * user's own operator branding: fetches GET /api/operator/branding and
 * writes the brand/accent CSS vars onto document.documentElement. Customers
 * and guests never fetch it - the :root defaults (src/index.css) stand.
 *
 * Per-*resource* branding (a customer's ticket, a tracking result - which
 * belong to some operator, not the viewer) is NOT handled here; those
 * components scope the operator colour to a single card via an inline
 * style built with themeVars().
 */
export function BrandingProvider({ children }) {
  const { authenticated, hasRole } = useAuth();
  const isStaff = authenticated && (hasRole('operator_admin') || hasRole('agent'));

  const { data } = useQuery({
    queryKey: ['operator', 'branding'],
    queryFn: () => apiGet('/api/operator/branding'),
    enabled: isStaff,
    staleTime: 5 * 60 * 1000,
  });

  useEffect(() => {
    const root = document.documentElement;
    if (!isStaff || !data) {
      VARS.forEach((v) => root.style.removeProperty(v));
      return undefined;
    }
    const vars = { ...themeVars(data.brandColor, 'brand'), ...themeVars(data.accentColor, 'accent') };
    Object.entries(vars).forEach(([k, val]) => root.style.setProperty(k, val));
    return () => VARS.forEach((v) => root.style.removeProperty(v));
  }, [isStaff, data]);

  return (
    <BrandingContext.Provider value={isStaff ? (data ?? null) : null}>
      {children}
    </BrandingContext.Provider>
  );
}

/** Staff-workspace branding ({ displayName, tagline, logoUrl, brandColor, accentColor }) or null. */
export function useBranding() {
  return useContext(BrandingContext);
}
