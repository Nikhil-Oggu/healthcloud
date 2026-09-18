# Input variables for the state-backend bootstrap (Phase 10, slice 6).
# Defaults mirror the main config so nothing extra is needed to run this.

variable "aws_region" {
  description = "AWS region for the Terraform state bucket. Match the main config's region."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Project name; used to build the state bucket name and tags."
  type        = string
  default     = "healthcloud"
}
