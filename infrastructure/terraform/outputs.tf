# Outputs (Phase 10, slice 5). For now just the conventions, so they're inspectable via
# `terraform output` and consumable by later modules. These need no AWS API calls.
output "name_prefix" {
  description = "Resource-name prefix for this environment (e.g. healthcloud-dev)."
  value       = local.name_prefix
}

output "common_tags" {
  description = "Tags applied to every resource via the provider default_tags."
  value       = local.common_tags
}
