# ── Amazon Cognito user pool (Phase 10, slice 11) ────────────────────────────────────
# The real identity provider that replaces the local dev-login stand-in (ADR-004: Cognito + a
# Spring Boot BFF). THIS SLICE IS INFRASTRUCTURE ONLY — it stands up the pool, an app client, the
# hosted login page, and two synthetic demo users. The backend/frontend wiring is the next slices.
#
# Cost: $0 (Cognito's free tier covers 50k monthly active users). Safe to leave up between slices —
# no hourly meter, unlike the ALB/Fargate.

# Account id → a globally-unique suffix for the hosted-UI domain prefix (Cognito domains are unique
# across ALL AWS accounts).
data "aws_caller_identity" "current" {}

resource "aws_cognito_user_pool" "main" {
  name = "${local.name_prefix}-users"

  # Users sign in with their email (matches the app's existing user emails), auto-verified.
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  password_policy {
    minimum_length    = 8
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = false
  }

  # MFA available but NOT enforced (keeps the demo login simple); enforcing TOTP is a hardening
  # follow-up. OPTIONAL requires at least one configured second factor — software token (TOTP).
  mfa_configuration = "OPTIONAL"
  software_token_mfa_configuration {
    enabled = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  # So `terraform destroy` can remove the pool cleanly on the on-demand cycle.
  deletion_protection = "INACTIVE"
}

# The app's registration with the pool. A CONFIDENTIAL client (has a secret) because the Spring BFF
# is server-side and holds the session — the standard BFF shape (ADR-004). The secret lands in the
# (private, encrypted) Terraform state; the backend will read it from config/Secrets Manager, never
# hardcoded.
resource "aws_cognito_user_pool_client" "app" {
  name         = "${local.name_prefix}-bff"
  user_pool_id = aws_cognito_user_pool.main.id

  generate_secret = true

  # OIDC authorization-code flow with the standard scopes.
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  supported_identity_providers         = ["COGNITO"]

  # Local-dev redirect URLs (Spring Security's default OAuth2 callback path). :8080 = the backend run
  # directly; :5173 = the Vite dev server (slice 13) — when the OIDC flow runs through the SPA's dev
  # proxy the backend computes the callback with the :5173 host, so Cognito must accept it too. The
  # deployed HTTPS URLs are added in the deploy-with-HTTPS slice — Cognito requires HTTPS for any
  # non-localhost callback.
  callback_urls = [
    "http://localhost:8080/login/oauth2/code/cognito",
    "http://localhost:5173/login/oauth2/code/cognito",
  ]
  logout_urls = [
    "http://localhost:8080/",
    "http://localhost:5173/",
  ]

  explicit_auth_flows = [
    "ALLOW_USER_SRP_AUTH",
    "ALLOW_USER_PASSWORD_AUTH",
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]

  # Don't reveal whether an email exists on failed sign-in.
  prevent_user_existence_errors = "ENABLED"
}

# The free Cognito-hosted login page: https://<prefix>.auth.<region>.amazoncognito.com
resource "aws_cognito_user_pool_domain" "main" {
  domain       = "${local.name_prefix}-${data.aws_caller_identity.current.account_id}"
  user_pool_id = aws_cognito_user_pool.main.id
}

# Two synthetic demo users, emails matching the seeded app users (provider = PROVIDER, admin =
# ORG_ADMIN), so slice 12's login maps straight onto existing roles. NO password is set here — that
# would land in state; a permanent synthetic password is set post-apply via `admin-set-user-password`
# (a documented CLI step). SUPPRESS = no invitation email is sent.
resource "aws_cognito_user" "seed" {
  for_each = toset([
    "provider@northcare.example.org",
    "admin@northcare.example.org",
  ])

  user_pool_id   = aws_cognito_user_pool.main.id
  username       = each.value
  message_action = "SUPPRESS"

  attributes = {
    email          = each.value
    email_verified = "true"
  }
}
