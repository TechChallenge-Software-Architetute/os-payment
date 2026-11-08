variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "app_name" {
  type    = string
  default = "os-payment"
}

variable "image_tag" {
  type    = string
  default = "latest"
}

variable "input_queue_name" {
  type    = string
  default = "sqs-os-management"
}

variable "output_queue_name" {
  type    = string
  default = "sqs-payment"
}

variable "desired_count" {
  type    = number
  default = 1
}
