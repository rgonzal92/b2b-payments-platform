# OpenBao's dev-mode setup for vault-service (M7), run by OpenBao itself on start (declarative self-initialization):
# compose.yaml and vault-service's TestOpenBao mount this folder as /openbao/config. Dev mode keeps everything in
# memory, so every start runs it again on an empty server. Not for the cluster (#140).

initialize "transit" {
  request "mount" {
    operation = "update"
    path      = "sys/mounts/transit"
    data      = { type = "transit" }
  }
  # The key-encryption key (KEK): it wraps every card's data key and never leaves OpenBao.
  request "card-kek" {
    operation = "update"
    path      = "transit/keys/card-kek"
    data      = { type = "aes256-gcm96" }
  }
}

initialize "vault-service" {
  # Everything vault-service may do: get a data key, unwrap one, and rewrap one under the KEK's newest version
  # (the rotation job, #139). Not read, rotate, export or delete the key, nor encrypt or decrypt its own data with it.
  request "policy" {
    operation = "update"
    path      = "sys/policies/acl/vault-service"
    data = {
      policy = <<-EOT
        path "transit/datakey/plaintext/card-kek" { capabilities = ["update"] }
        path "transit/decrypt/card-kek" { capabilities = ["update"] }
        path "transit/rewrap/card-kek" { capabilities = ["update"] }
      EOT
    }
  }
  # Tokens live at most 768 hours (32 days) by default; a dev stack can run longer than that.
  request "token-ttl" {
    operation = "update"
    path      = "sys/auth/token/tune"
    data      = { max_lease_ttl = "87600h" }
  }
  # vault-service's token, under that policy only, with the ID from .env (OPENBAO_TOKEN, scripts/init-env.sh). An
  # orphan: self-initialization revokes the root token it ran under, and with it every child token.
  request "token" {
    operation = "update"
    path      = "auth/token/create"
    data = {
      id = {
        eval_type       = "string"
        eval_source     = "env"
        env_var         = "OPENBAO_TOKEN"
        require_present = true
      }
      policies          = ["vault-service"]
      no_default_policy = true
      no_parent         = true
      ttl               = "87600h"
      display_name      = "vault-service"
    }
  }
}
