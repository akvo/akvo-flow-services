local cjson = require("cjson")
local openidc = require("resty.openidc")

local function is_empty (s)
   return string.len(string.gsub(s, "%s+", "")) == 0
end

local authorization = ngx.var.http_authorization

if not authorization or is_empty(authorization) then
   ngx.status = 401
   local error = cjson.encode({error="Authentication required"})
   ngx.say(error)
   ngx.exit(ngx.HTTP_UNAUTHORIZED)
end

local opts = {
   discovery = os.getenv("OIDC_DISCOVERY_URL"),
   token_signing_alg_values_expected = { "RS256" },
   accept_none_alg = false,
   accept_unsupported_alg = false,
   ssl_verify = "yes",
   expiry_claim = "exp"
}

local validators = require "resty.jwt-validators"
validators.set_system_leeway(120)

local claims = {
   exp = validators.is_not_expired(),
   nbf = validators.opt_is_not_before(),
   iss = validators.equals(os.getenv("OIDC_EXPECTED_ISSUER"))
}

local res, err, access_token = openidc.bearer_jwt_verify(opts, claims)
if err or not res then
   ngx.status = 403
   if err then
      local error = cjson.encode({error=err})
      ngx.say(err)
   else
      local error = cjson.encode({error="Invalid access_token"})
      ngx.say(error)
   end
   ngx.exit(ngx.HTTP_FORBIDDEN)
end

if res.email then
   if res["https://akvo.org/app_metadata"] and res["https://akvo.org/app_metadata"]["akvo-flow-services-sign"]  then
      ngx.req.set_header("X-Akvo-Email", res.email)
      ngx.var.akvoemail = res.email
   else
      ngx.log(ngx.ERR, "Authorization required for user " .. res.email)
      ngx.status = 403
      ngx.say(cjson.encode({error="Invalid access_token"}))
      ngx.exit(ngx.HTTP_FORBIDDEN)
   end
else
   ngx.status = 403
   ngx.say(cjson.encode({error="Invalid access_token"}))
   ngx.exit(ngx.HTTP_FORBIDDEN)
end
