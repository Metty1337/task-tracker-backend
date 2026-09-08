# REST API

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

## Tasks

All task endpoints require `Authorization: Bearer <JWT access token>`.
The owner is resolved from the JWT subject and must still exist in the database.
Client-supplied owner fields or query parameters never select another user.

`GET /tasks` implements the specification: HTTP **200** with a JSON array of the
current user's tasks (an empty array `[]` when there are none). The chosen ordering
is ascending task ID, without pagination.

The remaining endpoints and their contracts are local design choices:

| Method | Path | Success |
| --- | --- | --- |
| GET | /tasks/{id} | 200 with one owned task |
| POST | /tasks | 201 with the created task and a relative Location: /tasks/{id} header |
| PUT | /tasks/{id} | 200 with the updated task |
| DELETE | /tasks/{id} | 204 with an empty body |

Task responses contain only `id`, `title`, `description`, `status` and
`completedAt`; user credentials and the owner entity are never serialized:

~~~json
[
  {
    "id": 1,
    "title": "Read a chapter",
    "description": null,
    "status": "TODO",
    "completedAt": null
  }
]
~~~

POST accepts JSON with a required nonblank `title` (at most 255 characters)
and an optional string `description` (missing or null means no description).
New tasks always have status `TODO` and null `completedAt`.

PUT replaces the editable fields: `title` and `status` are required;
`description` is optional and is cleared when missing or null. The title has the
same validation as on creation and is stored as supplied. Status must be
`TODO` or `COMPLETED`. Changing TODO to COMPLETED sets the server's current
completion time; submitting COMPLETED again preserves that time, including when
editing text. Changing back to TODO clears the time. Completing a reopened task
records a new time. The client cannot set `completedAt`, `id` or the owner.
Unrecognized JSON fields are ignored, consistent with the application's JSON defaults.
Completion times use ISO-8601 UTC strings; PostgreSQL stores microsecond precision.

All errors have a JSON `message` field:

| Status | Cause | Message |
| --- | --- | --- |
| 400 | Invalid fields, malformed JSON, or a nonnumeric/out-of-range ID | Invalid request fields or body |
| 401 | Missing, invalid, expired JWT, invalid subject, or deleted user | Authentication required |
| 404 | Task absent or owned by another user | Task not found |
| 415 | Unsupported request content type | Unsupported Media Type |

401 responses include `WWW-Authenticate: Bearer`. A repeated deletion returns 404.
Updates and deletions look up tasks by both task ID and authenticated owner ID.

Examples (replace the token and task ID with real values):

~~~bash
curl -i http://localhost:8080/tasks \
  -H 'Authorization: Bearer <JWT access token>'

curl -i -X POST http://localhost:8080/tasks \
  -H 'Authorization: Bearer <JWT access token>' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Read a chapter","description":"Chapter 3"}'

curl -i http://localhost:8080/tasks/1 \
  -H 'Authorization: Bearer <JWT access token>'

curl -i -X PUT http://localhost:8080/tasks/1 \
  -H 'Authorization: Bearer <JWT access token>' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Read a chapter","description":"Chapter 3","status":"COMPLETED"}'

curl -i -X PUT http://localhost:8080/tasks/1 \
  -H 'Authorization: Bearer <JWT access token>' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Read again","status":"TODO"}'

curl -i -X DELETE http://localhost:8080/tasks/1 \
  -H 'Authorization: Bearer <JWT access token>'
~~~

The backend continues to own migrations through Liquibase. These endpoints reuse
the existing tasks table, owner index and status/completion constraints, so no
schema migration or change to `compose.dev.yaml` is required.
