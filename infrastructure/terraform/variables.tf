# Input variables for the HealthCloud infrastructure (Phase 10, slice 5).
# Sensible defaults so `validate`/`plan` run with no tfvars; override per environment later.

variable "aws_region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Project name; used as the prefix for resource names and the Project tag."
  type        = string
  default     = "healthcloud"
}

variable "environment" {
  description = "Deployment environment (drives naming + the Environment tag)."
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be one of: dev, staging, prod."
  }
}
