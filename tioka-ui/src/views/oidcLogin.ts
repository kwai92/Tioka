/** FE-05：OIDC 企业账号登录入口与回调页辅助（对齐 FND-05）。 */

export const DEFAULT_OIDC_IDP =
  (typeof import.meta !== 'undefined' && import.meta.env?.VITE_OIDC_IDP) || 'keycloak'

/** 仅允许同源相对路径，拒绝协议相对与外链。 */
export function normalizeOidcRedirect(redirect: string | null | undefined): string {
  if (redirect == null || redirect === '') {
    return '/'
  }
  const trimmed = redirect.trim()
  if (!trimmed.startsWith('/') || trimmed.startsWith('//')) {
    return '/'
  }
  return trimmed
}

export function buildOidcAuthorizeUrl(opts: {
  idp?: string
  redirect?: string | null
}): string {
  const idp = (opts.idp && opts.idp.trim()) || DEFAULT_OIDC_IDP
  const redirect = normalizeOidcRedirect(opts.redirect)
  return `/api/system/auth/oidc/${encodeURIComponent(idp)}/authorize?redirect=${encodeURIComponent(redirect)}`
}
