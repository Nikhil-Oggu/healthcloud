# Naming + tagging conventions every resource will reuse (Phase 10, slice 5).
# Centralized here so names and tags stay consistent across all future modules/resources.
locals {
  # Prefix for resource names, e.g. "healthcloud-dev-...". Keeps names collision-free per environment.
  name_prefix = "${var.project}-${var.environment}"

  # Applied to every resource via the provider's default_tags (see providers.tf), so we never
  # hand-tag individual resources. ManagedBy makes it obvious in the console that Terraform owns them.
  common_tags = {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "Terraform"
  }
}
