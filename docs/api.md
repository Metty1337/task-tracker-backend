# Authentication API

## POST /auth/login

Authenticates an existing user using a JSON body with `email` and `password`.
No existing access token is required. This endpoint accepts `application/json`, as requested.

Successful authentication returns HTTP **200**, an empty body, and
`Authorization: Bearer <JWT access token>`, matching registration's header convention.
The token uses the existing signing configuration and `app.jwt.ttl`; its subject is the user's ID.
Send this header with subsequent protected requests.

Email is stripped of surrounding whitespace and lowercased, as during registration.
Email must be valid and at most 254 characters. Password is required, nonblank, and at most
128 characters; it is compared exactly, including whitespace.

Chosen error contract (all errors are JSON with a `message` field):

| Status | Cause | Message |
| --- | --- | --- |
| 400 | Missing/invalid fields, empty body, or malformed JSON | Invalid request fields or body |
| 401 | Unknown email or incorrect password | Invalid email or password |
| 415 | Unsupported content type | Unsupported Media Type |
| 500 | Unexpected server failure | Internal server error |

Credential errors intentionally use the same status and message. They include
`WWW-Authenticate: Bearer` and never include an access token.
Login only reads the user and verifies the stored password hash; it does not register users or enqueue emails.

Example (use placeholder credentials for an account registered through `POST /user`):

~~~bash
curl -i -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"password-123"}'

curl -i http://localhost:8080/user \
  -H 'Authorization: Bearer <JWT access token>'
~~~
