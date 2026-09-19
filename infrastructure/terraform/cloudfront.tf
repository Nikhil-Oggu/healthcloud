# ── CloudFront (Phase 10 slice 15) — free HTTPS in front of the ALB ───────────────────
# Cognito rejects non-HTTPS callbacks for non-localhost, and ACM can't issue a cert for the ALB's
# *.elb.amazonaws.com name, so CloudFront gives a free trusted https://<id>.cloudfront.net endpoint
# over the HTTP-only ALB. The app is a dynamic BFF holding a session, so caching is DISABLED and all
# headers/cookies/query strings are forwarded to the origin (needed for the session + CSRF cookies and
# the OAuth code/state params).

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

resource "aws_cloudfront_distribution" "main" {
  enabled         = true
  is_ipv6_enabled = true
  comment         = "${local.name_prefix} — HTTPS in front of the ALB"
  price_class     = "PriceClass_100" # cheapest: North America + Europe edges only

  origin {
    domain_name = aws_lb.main.dns_name
    origin_id   = "alb"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "http-only" # the ALB has only an HTTP listener
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id         = "alb"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true # the free *.cloudfront.net certificate
  }

  tags = { Name = "${local.name_prefix}-cloudfront" }
}
