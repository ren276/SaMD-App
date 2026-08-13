VERIFY SANDBOX ACCESS
Sign in to check application status
Step 1: Once the Sandbox request form is submitted, the user can see the application submitted status by login in with Email id and Password. 
https://sandbox.ndhm.gov.in/applications/Home/login

SandboxLogin.png

Step 2: On login, Application Submitted status (the current application status) is displayed in green.

Landing_Page.png

 For additional information on the various application status

Step 3: Once the application is Approved by the committee, the user will receive an email containing your Client id & Client Secret. On Frontend, the user will see the status changed to Sandbox Application Status.
 

SandboxApplicationStatus.png

 If the status is approved and you havent received the client secret via email, kindly drop a mail to Integration.support@nha.gov.in. Please note that the committee currently meets once a week and it can take 7 - 10 days for you to get your application approved.

Step 4: Once Client id & Client Secret are received via an email, please verify it works by creating a gateway session token.

post
/v0.5/users/auth/fetch-modes
Get a patient's authentication modes relevant to specified purpose
This API is meant for identify supported authentication modes for a patient given a specific purpose

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "query": {
    "id": "hinapatel79@ndhm",
    "purpose": "LINK",
    "requester": {
      "type": "HIP",
      "id": "100005"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/users/auth/on-fetch-modes
Identification result for a consent-manager user-id
If a patient is found then auth attribute contains the supported modes for the specified purpose. Otherwise, error is raised for invalid requests or for non-existent id. Note in addition to the "Authorization" header, one of the following headers must be specified

X-HIU-ID if the requester is HIU (identified from /auth/fetch-modes requester.id)
X-HIP-ID if the requester is HIP (identified from /auth/fetch-modes requester.id)
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "auth": {
    "purpose": "LINK",
    "modes": [
      "MOBILE_OTP"
    ]
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-63d0-4000-8f1c-3826345b4780"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/users/auth/init
Initialize authentication from HIP
This API is called by HIPs to initiate authentication of users. A transactionId is retuned by the corresponding callback API for confirmation of user auth.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "query": {
    "id": "hinapatel@ndhm",
    "purpose": "LINK",
    "authMode": "MOBILE_OTP",
    "requester": {
      "type": "HIP",
      "id": 100005
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/on-init
Response to user authentication initialization from HIP
If the patient's id is valid, CM will return a transactionId as initialization of user auth. If the request is valid, then 'auth.mode' will convey how the authentication should be done. The authentication can be mediated or direct. For mediated authentication modes, HIP or HIU is epected to send over relevant code (OTP/token) or demographic info via subsequent API call to /auth/confirm. for direct authentication case, CM will notify requester through/users/auth/notify API.

auth.mode conveys whats the mode of authentication is, and what is expected from HIP/HIU in the subsequent /auth/confirm API call. Possible values
MOBILE_OTP - auth via OTP to registered mobile. Mediated.
AADHAAR_OTP - auth initiated with Aadhaar with OTP. Mediated.
DEMOGRAPHICS - auth initiated with demographic verification
DIRECT - for authentication directly with the patient. e.g. Mobile App, SMS. In this case, the HIP/HIU is not expected to call subsequent /auth/confirm call. CM will do direct authentication with the User (e.g. Mobile App, SMS etc) and will notify requester
meta.expiry conveys the expiry time of the token and the authentication session
NOTE, only one of X-HIP-ID or X-HIU-ID will be sent as part of header, not both.
The error section in the body, represents the potential errors that may have occurred. Possible reasons:

Patient id is invalid
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "auth": {
    "transactionId": "string",
    "mode": "MOBILE_OTP",
    "meta": {
      "hint": "string",
      "expiry": "2019-12-30T12:01:55Z"
    }
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-63e0-4000-8f8e-e0a7fc9beb01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/confirm
Confirmation request sending token, otp or other authentication details from HIP/HIU for confirmation
This API is called by HIP/HIUs to confirm authentication of users. The transactionId returned by the previous callback API /users/auth/on-init must be sent. If Authentication is successful the callback API will send an "access token" for subsequent purpose specific API calls. Note only credential.authCode or credential.demographic should be sent

demographic details are only required for demographic auth as of now.
demographic details are required only in MEDIATED cases and if the auth.mode so demands. e.g. if auth.mode is DEMOGRAPHICS. Usually for demographic authentication, the name, gender and DOB must be exactly as specified in User Account.
demographic.identifier is optional, however maybe required if authentication so mandates.
credential.authCode is required for other MEDIATED authentication like MOBILE_OTP, AADHAAR_OTP.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "string",
  "credential": {
    "authCode": "string",
    "demographic": {
      "name": "janki das",
      "gender": "M",
      "dateOfBirth": "1972-02-29",
      "identifier": {
        "type": "MOBILE",
        "value": "+919800083232"
      }
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/on-confirm
callback API for /auth/confirm (in case of MEDIATED auth) to confirm user authentication or not
This API is called by CM to confirm authentication of users.

auth.accessToken - is specific to the purpose mentioned in the /auth/init. This token needs to be used for initiating the intended action. For example for HIP initiated linking of care-contexts
NOTE, only one of X-HIP-ID or X-HIU-ID will be sent as part of header, not both.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "auth": {
    "accessToken": "string",
    "validity": {
      "purpose": "LINK",
      "requester": {
        "type": "HIP",
        "id": 100005
      },
      "expiry": "1970-01-01T00:00:00.000Z",
      "limit": "1"
    },
    "patient": {
      "id": "<patient-id>@<consent-manager-id>",
      "name": "Hina Patel",
      "gender": "M",
      "yearOfBirth": 2000,
      "address": {
        "line": "string",
        "district": "string",
        "state": "string",
        "pincode": "string"
      },
      "identifiers": [
        {
          "type": "MR",
          "value": "+919800083232"
        }
      ]
    }
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-63e0-4000-82f8-a832c6294580"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/notify
notification API in case of DIRECT mode of authentication by the CM
This API is called by CM to confirm authentication of users. The transactionId returned is same as that passed in /auth/on-init. The "auth.status" conveys whether the request was GRANTED or DENIED.

auth.accessToken - is specific to the purpose mentioned in the /auth/init. This token needs to be used for initiating the intended action. For example for HIP initiated linking of care-contexts
NOTE, only one of X-HIP-ID or X-HIU-ID will be sent as part of header, not both.
The payload is conditional to the purpose of auth. If purpose specified in /auth/init is KYC or KYC_AND_LINK, then patient details are passed. auth.accessToken is passed only if the purpose is LINK or KYC_AND_LINK.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "auth": {
    "transactionId": "string",
    "status": "GRANTED",
    "accessToken": "string",
    "validity": {
      "purpose": "LINK",
      "requester": {
        "type": "HIP",
        "id": 100005
      },
      "expiry": "1970-01-01T00:00:00.000Z",
      "limit": "1"
    },
    "patient": {
      "id": "<patient-id>@<consent-manager-id>",
      "name": "Hina Patel",
      "gender": "M",
      "yearOfBirth": 2000,
      "address": {
        "line": "string",
        "district": "string",
        "state": "string",
        "pincode": "string"
      },
      "identifiers": [
        {
          "type": "MR",
          "value": "+919800083232"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/on-notify
callback API by HIU/HIPs as acknowledgement of auth notification
This API is called by HIU/HIPs to confirm acknowledgement for receipt of auth notification is case of DIRECT authentication.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "OK"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-63f0-4000-8794-c73d22f7de01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/patients/profile/share
deprecated
Share patient profile details
Request for sharing patient's profile details to HIP

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "profile": {
    "hipCode": "12345 (CounterId)",
    "patient": {
      "healthId": "<username>@<suffix>",
      "healthIdNumber": "1111-1111-1111-11",
      "name": "Jane Doe",
      "gender": "M",
      "address": {
        "line": "string",
        "district": "string",
        "state": "string",
        "pincode": "string"
      },
      "yearOfBirth": 2000,
      "dayOfBirth": 0,
      "monthOfBirth": 0,
      "identifiers": [
        {
          "type": "MR",
          "value": "+919800083232"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request accepted

post
/v1.0/patients/profile/share
Share patient profile details
Request for sharing patient's profile details to HIP

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "intent": {
    "type": "string"
  },
  "location": {
    "latitude": "string",
    "longitude": "string"
  },
  "profile": {
    "hipCode": "12345 (CounterId)",
    "patient": {
      "healthId": "<username>@<suffix>",
      "healthIdNumber": "1111-1111-1111-11",
      "name": "Jane Doe",
      "gender": "M",
      "address": {
        "line": "string",
        "district": "string",
        "state": "string",
        "pincode": "string"
      },
      "yearOfBirth": 2000,
      "dayOfBirth": 0,
      "monthOfBirth": 0,
      "identifiers": [
        {
          "type": "MR",
          "value": "+919800083232"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/patients/profile/on-share
deprecated
Response to patient's share profile request
Result of patient share profile request at HIP end.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "SUCCESS",
    "healthId": "<username>@<suffix>"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-63f0-4000-8412-a113e53b2880"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v1.0/patients/profile/on-share
Response to patient's share profile request
Result of patient share profile request at HIP end.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "SUCCESS",
    "healthId": "<username>@<suffix>",
    "tokenNumber": "string"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-63f0-4000-8eb3-f776a6022080"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/care-contexts/discover
Discover patient's accounts
Request for patient care context discover, made by CM for a specific HIP. It is expected that HIP will subsequently return either zero or one patient record with (potentially masked) associated care contexts

At least one of the verified identifier matches
Name (fuzzy), gender matches
If YoB was given, age band(+-2) matches
If unverified identifiers were given, one of them matches
If more than one patient records would be found after aforementioned steps, then patient who matches most verified and unverified identifiers would be returned.
If there would be still more than one patients (after ranking) error would be returned
Intended HIP should be able to resolve and identify results returned in the subsequent link confirmation request via the specified transactionId
Intended HIP should store the discovery results with transactionId and care contexts discovered for subsequent link initiation
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "19ffab9b-63f0-4000-8108-c33215f65701",
  "patient": {
    "id": "<patient-id>@<consent-manager-id>",
    "verifiedIdentifiers": [
      {
        "type": "MR",
        "value": "+919800083232"
      }
    ],
    "unverifiedIdentifiers": [
      {
        "type": "MR",
        "value": "+919800083232"
      }
    ],
    "name": "chandler bing",
    "gender": "M",
    "yearOfBirth": 2000
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/care-contexts/on-discover
Response to patient's account discovery request
Result of patient care-context discovery request at HIP end. If a matching patient found with zero or more care contexts associated, it is specified as result attribute. If the prior discovery request, resulted in errors then it is specified in the error attribute. Reasons of errors can be

more than one definitive match for the given request
no verified identifer was specified
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "19ffab9b-6400-4000-8e98-9a931d3ca501",
  "patient": {
    "referenceNumber": "string",
    "display": "string",
    "careContexts": [
      {
        "referenceNumber": "string",
        "display": "string"
      }
    ],
    "matchedBy": [
      "MR"
    ]
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6400-4000-8fa3-aa0f8d974d80"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/links/link/init
Link patient's care contexts
Request from CM to links care contexts associated with only one patient

Validate account reference number and care context reference number
Validate transactionId in the request with discovery request entry to check whether there was a discovery and were these care contexts discovered or not for a given patient
Before eventual link confirmation, HIP needs to authenticate the request with the patient(eg: OTP verification)
HIP should communicate the mode of authentication of a successful request to Consent Manager
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "19ffab9b-6400-4000-86b9-ad6478dc3b80",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "19ffab9b-6400-4000-862c-b84c9619fd01",
  "patient": {
    "id": "hinapatel79@ndhm",
    "referenceNumber": "TMH-PUID-001",
    "careContexts": [
      {
        "referenceNumber": "string"
      }
    ]
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/links/link/on-init
Response to patient's care context link request
Result of patient care-context link request from HIP end. This happens in context of previous discovery of patient found at HIP end, therefore the link requests ought to be in reference to the patient reference and care-context references previously returned by the HIP. The correlation of discovery and link request is maintained through the transactionId. HIP should have

Validated transactionId in the request to check whether there was a discovery done previously, and the link request corresponds to returned patient care care context references
Before returning the response, HIP should have sent an authentication request to the patient(eg: OTP verification)
HIP should communicate the mode of authentication of a successful request
HIP subsequently should expect the token passed via /link/confirm against the link.referenceNumber passed in this call
The error section in the body, represents the potential errors that may have occurred. Possible reasons:

Patient reference number is invalid
Care context reference numbers are invalid
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "link": {
    "referenceNumber": "string",
    "authenticationType": "DIRECT",
    "meta": {
      "communicationMedium": "MOBILE",
      "communicationHint": "string",
      "communicationExpiry": "2019-12-30T12:01:55Z"
    }
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6400-4000-821e-c3e9b81c4080"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/links/link/confirm
Token submission by Consent Manager for link confirmation
API to submit the token that was sent by HIP during the link request.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "19ffab9b-6400-4000-869e-654ebeb3f380",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "confirmation": {
    "linkRefNumber": "string",
    "token": "string"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
accepted

post
/v0.5/links/link/on-confirm
Token authenticated by HIP, indicating completion of linkage of care-contexts
Returns a list of linked care contexts with patient reference number.

Validated and linked account reference number
Validated that the token sent from Consent Manager is same as the one generated by HIP
Verified that same Consent Manager which made the link request is sending the token
Results of unmasked linked care contexts with patient reference number
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "patient": {
    "referenceNumber": "string",
    "display": "string",
    "careContexts": [
      {
        "referenceNumber": "string",
        "display": "string"
      }
    ]
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6400-4000-8264-82aa237b5101"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
accepted

post
/v0.5/links/link/add-contexts
API for HIP initiated care-context linking for patient
API to submit care-context to CM for HIP initiated linking. The API must accompany the "accessToken" fetched in the users/auth process.

subsequent usage for accessToken may be invalid if it was meant for one-time usage or if it expired
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "link": {
    "accessToken": "string",
    "patient": {
      "referenceNumber": "TMH-PUID-001",
      "display": "string",
      "careContexts": [
        {
          "referenceNumber": "string",
          "display": "string"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
accepted

post
/v0.5/links/link/on-add-contexts
callback API for HIP initiated patient linking /link/add-context
If the accessToken is valid for purpose of linking, and specified details provided, CM will send "acknoweldgement.status" as SUCCESS. If any error occcurred, for example invalid token, or other required patient or care-context information not provided, then "error" attribute conveys so.

accessToken must be valid and must be for the purpose of linking
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "SUCCESS"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6400-4000-8ca6-b6845409b019"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
accepted

post
/v0.5/links/context/notify
This API is meant to be called by HIPs when there is new health data generated for a patient, against a care context that is already linked to patient's ABDM account.
This API is called by HIP only when there is new health data is added/created for a patient and under a care context that is already linked with patient's Health Account. HIP can send following things in this API to notify the Consent Manager about the new health data added:

Patient's Identifier for which the new health data is added (It can be ABDM id or ABDM number)
Care Context reference under which the new health data is added
Patient's reference (An identifier with which the patient is registered on HIP)
Types of health information documents that have been added
A date when the health information was created/added on the HIP Note: This API shouldn't be called if the new heath data of is added/created under new care context.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "patient": {
      "id": "hinapatel@ncg"
    },
    "careContext": {
      "patientReference": "batman@tmh",
      "careContextReference": "Episode1"
    },
    "hiTypes": [
      "OPConsultation"
    ],
    "date": "1970-01-01T00:00:00.000Z",
    "hip": {
      "id": 1000010
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
accepted

post
/v0.5/links/context/on-notify
Acknowledgement sent by Consent Manager to HIP for data notification.
CM sends back acknowledgement of receiving data notification by HIPs. CM may return errors if in following scenarios:

Patient id sent by HIP in the data notification is incorrect
Carecontext sent by HIP in the data notification is not linked or incorrect.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "SUCCESS"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6410-4000-83e1-f7bde6ae4c80"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
accepted

post
/v0.5/consent-requests/init
Create consent request
Creates a consent request to get data about a patient by HIU user.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consent": {
    "purpose": {
      "text": "string",
      "code": "string",
      "refUri": "http://example.com"
    },
    "patient": {
      "id": "hinapatel79@ndhm"
    },
    "hip": {
      "id": "string"
    },
    "careContexts": [
      {
        "patientReference": "batman@tmh",
        "careContextReference": "Episode1"
      }
    ],
    "hiu": {
      "id": "string"
    },
    "requester": {
      "name": "Dr. Manju",
      "identifier": {
        "type": "REGNO",
        "value": "MH1001",
        "system": "https://www.mciindia.org"
      }
    },
    "hiTypes": [
      "OPConsultation"
    ],
    "permission": {
      "accessMode": "VIEW",
      "dateRange": {
        "from": "1970-01-01T00:00:00.000Z",
        "to": "1970-01-01T00:00:00.000Z"
      },
      "dataEraseAt": "1970-01-01T00:00:00.000Z",
      "frequency": {
        "unit": "HOUR",
        "value": 0,
        "repeats": 0
      }
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consent-requests/on-init
Response to consent request
Result of consent request creation for a patient. consentRequest.id represents the consentrequest id created by CM. The result must contain either consentRequest or the error caused.
Reasons for error may be

Invalid references (e.g patient id, hiu id), purpose, hiTypes, ranges, persmission
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consentRequest": {
    "id": "f29f0e59-8388-4698-9fe6-05db67aeac46"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6410-4000-8245-eba502eab901"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consent-requests/status
Get consent request status
Get status of consent request done previously

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consentRequestId": "string"
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consent-requests/on-status
Result of consent request status
Result of consent request done previously. Status of request can be GRANTED, DENIED, EXPIRED. If the request was GRANTED, then

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consentRequest": {
    "id": "<consent-request-id>",
    "status": "GRANTED",
    "consentArtefacts": [
      {
        "id": "<consent-artefact-id>"
      }
    ]
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6410-4000-81f0-1f743e23f280"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consents/hip/notify
Consent notification
Notification of consents to health information providers consent request granted, consent revoked, consent expired. Only the GRANTED, REVOKED and EXPIRED status notifications will be sent to HIP.

If consent is granted, status=GRANTED, then consentDetail contains the consent artefact details and signature is available.
If consent is revoked, then status=REVOKED, and consentId specifes which consent artefact is revoked.
If the consent has expired, then status=EXPIRED, and consentId specifies which consent artefact has expired. Note, this is also responsibility of the HIP to keep track of consent expiry. Any data request on expired consent artefact must not be done.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "status": "GRANTED",
    "consentId": "19ffab9b-6420-4000-8b79-7eb7bebb3280",
    "consentDetail": {
      "schemaVersion": "",
      "consentId": "19ffab9b-6420-4000-80cb-9bfe5081a580",
      "createdAt": "1970-01-01T00:00:00.000Z",
      "patient": {
        "id": "hinapatel79@ndhm"
      },
      "careContexts": [
        {
          "patientReference": "hinapatel79@hospital",
          "careContextReference": "Episode1"
        }
      ],
      "purpose": {
        "text": "string",
        "code": "string",
        "refUri": "http://example.com"
      },
      "hip": {
        "id": "string"
      },
      "consentManager": {
        "id": "string"
      },
      "hiTypes": [
        "OPConsultation"
      ],
      "permission": {
        "accessMode": "VIEW",
        "dateRange": {
          "from": "1970-01-01T00:00:00.000Z",
          "to": "1970-01-01T00:00:00.000Z"
        },
        "dataEraseAt": "1970-01-01T00:00:00.000Z",
        "frequency": {
          "unit": "HOUR",
          "value": 0,
          "repeats": 0
        }
      }
    },
    "signature": "Signature of CM as defined in W3C standards; Base64 encoded"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consents/hip/on-notify
Consent notification
This API is called by HIP as acknowledgement to notification of consents, in cases of consent revocation and expiration.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "OK",
    "consentId": "<consent-artefact-id>"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6420-4000-879e-8ccf4f4e2780"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consents/hiu/notify
Consent notification
Health information user will get notified about the consent request granted or denied, consent revoked, consent expired.

For consent request grant, status=GRANTED, consentRequestId=, and consentArtefacts is an array of generated consent artefact Ids.
For consent request expiry, status=EXPIRED, consentRequestId=
For consent request denied, status=DENIED, consentRequestId=
For consent revocation, status=REVOKED, consentArtefacts is an array of revoked consent artefact ids
REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "consentRequestId": "<consent-request-id>",
    "status": "GRANTED",
    "consentArtefacts": [
      {
        "id": "<consent-artefact-id>"
      }
    ]
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted.

post
/v0.5/consents/hiu/on-notify
Consent notification
This API is called by HIU as acknowledgement to consent notifications, specifically for cases when consent is REVOKED or EXPIRED.

REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": [
    {
      "status": "OK",
      "consentId": "<consent-artefact-id>"
    }
  ],
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6420-4000-80b2-1821c1b66a80"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted.

post
/v0.5/consents/fetch
Get consent artefact
REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consentId": "string"
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consents/on-fetch
Result of fetch request for a consent artefact
Must contain either consentDetail or error. Possible reason of errors are

consentId passed through /fetch is invalid
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consent": {
    "status": "GRANTED",
    "consentDetail": {
      "schemaVersion": "",
      "consentId": "19ffab9b-6420-4000-8ae9-7e15ffed4201",
      "createdAt": "1970-01-01T00:00:00.000Z",
      "patient": {
        "id": "hinapatel79@ndhm"
      },
      "careContexts": [
        {
          "patientReference": "hinapatel79@hospital",
          "careContextReference": "Episode1"
        }
      ],
      "purpose": {
        "text": "string",
        "code": "string",
        "refUri": "http://example.com"
      },
      "hip": {
        "id": "string"
      },
      "hiu": {
        "id": "string"
      },
      "consentManager": {
        "id": "string"
      },
      "requester": {
        "name": "Dr. Manju",
        "identifier": {
          "type": "REGNO",
          "value": "MH1001",
          "system": "https://www.mciindia.org"
        }
      },
      "hiTypes": [
        "OPConsultation"
      ],
      "permission": {
        "accessMode": "VIEW",
        "dateRange": {
          "from": "1970-01-01T00:00:00.000Z",
          "to": "1970-01-01T00:00:00.000Z"
        },
        "dataEraseAt": "1970-01-01T00:00:00.000Z",
        "frequency": {
          "unit": "HOUR",
          "value": 0,
          "repeats": 0
        }
      }
    },
    "signature": "Signature of CM as defined in W3C standards; Base64 encoded"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6420-4000-890e-6a0f06994201"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/health-information/cm/request
Health information data request
Request for Health information against a consent id. CM would generate a transactionId against each consent and pass it as trnasaction context / correlation id to the HIP and also return the same to HIU via /on-request.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "hiRequest": {
    "consent": {
      "id": "string"
    },
    "dateRange": {
      "from": "1970-01-01T00:00:00.000Z",
      "to": "1970-01-01T00:00:00.000Z"
    },
    "dataPushUrl": "string",
    "keyMaterial": {
      "cryptoAlg": "ECDH",
      "curve": "Curve25519",
      "dhPublicKey": {
        "expiry": "1970-01-01T00:00:00.000Z",
        "parameters": "Curve25519/32byte random key",
        "keyValue": ""
      },
      "nonce": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/health-information/cm/on-request
Health information data request
Callback API for acknowledgement of Health information request of HIU. CM calls this API when it has validated the Health Information request given the consent id. Either the hiRequest or error would need to be specified. If the health info request was valid, then the hiRequest.transactionId specifies the transaction context against which HIP would send over the data. Possible cases of errors are

Invalid consent artefact id
Consent has expired
Date ranges are invalid
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "hiRequest": {
    "transactionId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "sessionStatus": "REQUESTED"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6430-4000-8485-0534c70eba01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/health-information/notify
Notifications corresponding to events during data flow
API called by HIU and HIP during data-transfer.

HIP on transfer of data would send sessionStatus - one of [TRANSFERRED, FAILED]
HIP would also send hiStatus for each careContextReference - on of [DELIVERED, ERRORED]
HIU on receipt of data would send sessionStatus - one of [TRANSFERRED, FAILED]. For example, FAILED when if data was not sent or if invalid data was sent
HIU would also send hiStatus for each careContextReference - one of [OK, ERRORED]
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "consentId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "transactionId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "doneAt": "1970-01-01T00:00:00.000Z",
    "notifier": {
      "type": "HIU",
      "id": "tmh"
    },
    "statusNotification": {
      "sessionStatus": "TRANSFERRED",
      "hipId": "max",
      "statusResponses": [
        {
          "careContextReference": "string",
          "hiStatus": "OK",
          "description": "string"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Notification is Accepted

post
/v0.5/health-information/hip/request
Health information data request
API called by CM to request Health information from HIP against a validated consent artefact. The transactionId is the correlation id that HIP should use use when pushing data to the dataPushUrl.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "hiRequest": {
    "consent": {
      "id": "string"
    },
    "dateRange": {
      "from": "1970-01-01T00:00:00.000Z",
      "to": "1970-01-01T00:00:00.000Z"
    },
    "dataPushUrl": "string",
    "keyMaterial": {
      "cryptoAlg": "ECDH",
      "curve": "Curve25519",
      "dhPublicKey": {
        "expiry": "1970-01-01T00:00:00.000Z",
        "parameters": "Curve25519/32byte random key",
        "keyValue": ""
      },
      "nonce": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request accepted.

post
/v0.5/health-information/hip/on-request
Health information data request
API called by HIP to acknowledge Health information request receipt. Either the hiRequest or error must be specified. hiRequest element returns the same transactionId as before with a status indicating that the request is acknowledged.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "hiRequest": {
    "transactionId": "19ffab9b-6430-4000-8582-16114da36601",
    "sessionStatus": "ACKNOWLEDGED"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6430-4000-8a38-a6042c899280"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted.

post
/v0.5/patients/find
Identify a patient by her consent-manager user-id
This API is meant for identify to patient given her consent-manager-user-id

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "query": {
    "patient": {
      "id": "hinapatel79@ndhm"
    },
    "requester": {
      "type": "HIU",
      "id": 100005
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/patients/on-find
Identification result for a consent-manager user-id
If a patient is found then patient.name contains the patients name. Otherwise, patient is not provided, and possibly error is raised for invalid requests Note in addition to the "Authorization" header, one of the following headers must be specified

specify X-HIU-ID if the requester is HIU (identified from /find requester.id)
specify X-HIP-ID if the requester is HIP (identified from /find requester.id)
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "patient": {
    "id": "hinapatel79@ndhm",
    "name": "Hina Patel"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6430-4000-80c0-36b986849e01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

RESPONSE
Request Accepted

get
/v0.5/heartbeat
Informs about server status
REQUEST
RESPONSE
200
OK

EXAMPLE
SCHEMA

application/json
Copy
{
"timestamp": "1970-01-01T00:00:00.000Z",
"status": "UP",
"error": {
"code": 0,
"message": "string"
}
}
post
/v0.5/sessions
Get access token
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "clientId": "string",
  "clientSecret": "string",
  "grantType": "client_credentials",
  "refreshToken": "string"
}
RESPONSE
OK

EXAMPLE
SCHEMA

application/json
Copy
{
"accessToken": "eyJhbGciOiJSUzI1Ni.IsInR5cCIgOiAiSldUIiwia2lkIiA6ICJrVVp.2MXJQMjRyYXN1UW9wU2lWbkdZQUZIVFowYVZGVWpYNXFLMnNibTk0In0",
"expiresIn": 1800,
"refreshExpiresIn": 1800,
"refreshToken": "eyJhbGciOiJSUzI1Ni.IsInR5cCIgOiAiSldUIiwia2lkIiA6ICJrVVp.2MXJQMjRyYXN1UW9wU2lWbkdZQUZIVFowYVZGVWpYNXFLMnNibTk0In0",
"tokenType": "bearer"
}
get
/v0.5/.well-known/openid-configuration
Get openid configuration
REQUEST
RESPONSE
OK

EXAMPLE
SCHEMA

application/json
Copy
{
"jwks_uri": "https://ndhm-gateway/certs"
}
get
/v0.5/certs
Get certs for JWT verification
REQUEST
RESPONSE
OK

EXAMPLE
SCHEMA

application/json
Copy
{
"keys": [
{
"e": "AQAB",
"kid": "AlRb5WCm8Tm9EJ_IfO9z06j9oCv51pKKFknGb_TBvK0",
"kty": "RSA",
"n": "mgmW7W5ZGF_G5cJevwYi8HiPcI-6qS_psnZxa4v3bkwAkyOoOd8-6ketrOI-ZA2PbRbGnxFfZHiI94rdFXJ4Q9ampscsz9NocTIPMPmWydJ8A50pZaYWyikYDSJiDltq7i3WspPKSOuQHrC5h9dMcCVveX5oeg0tO68Z79gwDlpcxiqDbFaphsqDvx-5XkfwiqvOBaybK6_BCBPuTqWMUEuUklLYXu2X7ESHdVNFMFAjxCcCXUtP7LFdvT3nnFekRmG82QbSQSVe4N5tPH8q0MCxSWWn2c15bDnzOF-dvfRCVPRabCzw0M-utHR9diTrWtq6Koi5buxgwM1rbk0p8Q",
"use": "sig",
"x5c": [
"MIICrzCCAZcCBgFy/3WZBjANBgkqhkiG9w0BAQsFADAbMRkwFwYDVQQDDBBjZW50cmFsLXJlZ2lzdHJ5MB4XDTIwMDYyOTA5NDEzNloXDTMwMDYyOTA5NDMxNlowGzEZMBcGA1UEAwwQY2VudHJhbC1yZWdpc3RyeTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJoJlu1uWRhfxuXCXr8GIvB4j3CPuqkv6bJ2cWuL925MAJMjqDnfPupHraziPmQNj20Wxp8RX2R4iPeK3RVyeEPWpqbHLM/TaHEyDzD5lsnSfAOdKWWmFsopGA0iYg5bau4t1rKTykjrkB6wuYfXTHAlb3l+aHoNLTuvGe/YMA5aXMYqg2xWqYbKg78fuV5H8IqrzgWsmyuvwQgT7k6ljFBLlJJS2F7tl+xEh3VTRTBQI8QnAl1LT+yxXb0955xXpEZhvNkG0kElXuDebTx/KtDAsUllp9nNeWw58zhfnb30QlT0Wmws8NDPrrR0fXYk61rauiqIuW7sYMDNa25NKfECAwEAATANBgkqhkiG9w0BAQsFAAOCAQEACkC3TijrXIgi4vn+l1uL1nfdK6vOIL5UZ6yCjSOq7zYW6b3Qe8j7NrPb9RJC+pbIERyNbB+t9hsa5g1L7lkjCNlUuxfJprsJ9LJKlM5g7dYEA6XPCJ7C6AVlarj72vlWXQvwjnQMO2/CM9/Jp5Hnv2Qwjn7NME2OWM0iblc/TD+DEZK5L5mlWMyuBSQo2o/AcOmfG4MoE5Gm/CaOJ47rSrf+lq83e5+dyKh7uLVAa+5WK8Im5nEs6BLSGyo2KlaV0mW9yCkoRLLbipjH8+rJwkUU6iu7QVjz0peGZzYldya5n35gMWH7Bu4HqFneKNRwwD6w8rGNC+uWtgWejDZ3yQ=="
],
"x5t": "EaMhYGUIvMkp8tvSM3QoaqaF8xM",
"x5t#S256": "vGer6Pt8AhZn8RlbHhAFksOCcGf3u1UWU7Qq-Doy7ro",
"alg": "RS256"
}
]
}
post
/v0.5/subscription-requests/cm/init
Request for subscription
creates a request for subscription. The subscription categories can be for care-contexts linkages or availability of data against existing care-contexts. Note that the requester must have HIU role

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "subscription": {
    "purpose": {
      "text": "string",
      "code": "string",
      "refUri": "http://example.com"
    },
    "patient": {
      "id": "hinapatel79@ndhm"
    },
    "hiu": {
      "id": "string"
    },
    "hips": [
      {
        "id": "string"
      }
    ],
    "categories": [
      "LINK"
    ],
    "period": {
      "from": "1970-01-01T00:00:00.000Z",
      "to": "1970-01-01T00:00:00.000Z"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/subscription-requests/cm/on-init
callback API for the /subscription-requests/cm/init to notify a HIU on acceptance/acknowledgement of the request for subscription.
This callback API acknowledges the request for subscription from a HIU, and sends back a "id" that will be used when the patient/user approves or denies the subscription.

REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "subscriptionRequest": {
    "id": "f29f0e59-8388-4698-9fe6-05db67aeac46"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6440-4000-81bc-da42ff9df480"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted.

post
/v0.5/subscription-requests/hiu/notify
Notification for subscription grant/deny/revoke
This API is used by CM to notify a HIU to grant or deny a request for subscription, and also to notify that in case an existing subscription is revoked or expired. For notifying that a particular subscription request was GRANTED or DENIED, the subscriptionRequestId is passed.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "subscriptionRequestId": "request id of the subscription",
    "status": "GRANTED",
    "subscription": {
      "id": "subscription Id",
      "patient": {
        "id": "hinapatel79@ndhm"
      },
      "hiu": {
        "id": "string"
      },
      "sources": [
        {
          "hip": {
            "id": "string"
          },
          "categories": [
            "LINK"
          ],
          "period": {
            "from": "1970-01-01T00:00:00.000Z",
            "to": "1970-01-01T00:00:00.000Z"
          }
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/subscription-requests/hiu/on-notify
Callback API for /subscription-requests/hiu/notify to acknowledge receipt of notification.
This API is called by HIU as acknowledgement to subscription request relevant notifications.

REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "OK",
    "subscriptionRequestId": "subscription Id"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6440-4000-82ec-9a5b39ced501"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted.

post
/v0.5/subscriptions/hiu/notify
Notification to HIU on basis of a granted subscription
This API is used by CM to notify a HIU for notification relevant to subscription. Notifications are sent to subscribed HIUs whenever a new care-context is linked or new data is available on an existing linked care-context.

if event.category = LINK, then only care-contexts are passed when new care-contexts are linked for patient.
If event.category = DATA, then hiTypes are passed. Care-context is passed only if the subscribed HIU has any valid consent for that care-context
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "event": {
    "id": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "published": "1970-01-01T00:00:00.000Z",
    "subscriptionId": "subscription Id",
    "category": "LINK",
    "content": {
      "patient": {
        "id": "hinapatel79@ndhm"
      },
      "hip": {
        "id": "string"
      },
      "context": [
        {
          "careContext": {
            "patientReference": "batman@tmh",
            "careContextReference": "Episode1"
          },
          "hiTypes": [
            "OPConsultation"
          ]
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/subscriptions/hiu/on-notify
Callback API for /subscriptions/hiu/notify to acknowledge receipt of notification.
This API is called by HIU as acknowledgement to consent notifications, specifically for cases when consent is REVOKED or EXPIRED.

REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "OK",
    "eventId": "subscription event Id"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6450-4000-8893-0b7ea76a9201"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted.

get
/v0.5/hi-services/{service-id}
Get bridge service details/profile by the serviceId provided.
This API is meant for displaying the bridge service details by the serviceId provided .

REQUEST
PATH PARAMETERS
* service-id
string
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

RESPONSE
service details fetched successfully

EXAMPLE
SCHEMA

application/json
Copy
{
"id": "string",
"name": "string",
"type": "HIP",
"endpoints": [
{
"use": "string",
"connectionType": "string",
"address": "string"
}
],
"active": false
}
post
/v0.5/patients/profile/share
deprecated
Share patient profile details
Request for sharing patient's profile details to HIP

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "profile": {
    "hipCode": "12345 (CounterId)",
    "patient": {
      "healthId": "<username>@<suffix>",
      "healthIdNumber": "1111-1111-1111-11",
      "name": "Jane Doe",
      "gender": "M",
      "address": {
        "line": "string",
        "district": "string",
        "state": "string",
        "pincode": "string"
      },
      "yearOfBirth": 2000,
      "dayOfBirth": 0,
      "monthOfBirth": 0,
      "identifiers": [
        {
          "type": "MR",
          "value": "+919800083232"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/care-contexts/discover
Discover patient's accounts
Request for patient care context discover, made by CM for a specific HIP. It is expected that HIP will subsequently return either zero or one patient record with (potentially masked) associated care contexts

At least one of the verified identifier matches
Name (fuzzy), gender matches
If YoB was given, age band(+-2) matches
If unverified identifiers were given, one of them matches
If more than one patient records would be found after aforementioned steps, then patient who matches most verified and unverified identifiers would be returned.
If there would be still more than one patients (after ranking) error would be returned
Intended HIP should be able to resolve and identify results returned in the subsequent link confirmation request via the specified transactionId
Intended HIP should store the discovery results with transactionId and care contexts discovered for subsequent link initiation
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "19ffab9b-6450-4000-8642-630870d43401",
  "patient": {
    "id": "<patient-id>@<consent-manager-id>",
    "verifiedIdentifiers": [
      {
        "type": "MR",
        "value": "+919800083232"
      }
    ],
    "unverifiedIdentifiers": [
      {
        "type": "MR",
        "value": "+919800083232"
      }
    ],
    "name": "chandler bing",
    "gender": "M",
    "yearOfBirth": 2000
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/care-contexts/on-discover
Response to patient's account discovery request
Result of patient care-context discovery request at HIP end. If a matching patient found with zero or more care contexts associated, it is specified as result attribute. If the prior discovery request, resulted in errors then it is specified in the error attribute. Reasons of errors can be

more than one definitive match for the given request
no verified identifer was specified
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "19ffab9b-6450-4000-84e4-1821f94e8a80",
  "patient": {
    "referenceNumber": "string",
    "display": "string",
    "careContexts": [
      {
        "referenceNumber": "string",
        "display": "string"
      }
    ],
    "matchedBy": [
      "MR"
    ]
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6450-4000-8ddd-955cba6dbe01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/links/link/init
Link patient's care contexts
Request from CM to links care contexts associated with only one patient

Validate account reference number and care context reference number
Validate transactionId in the request with discovery request entry to check whether there was a discovery and were these care contexts discovered or not for a given patient
Before eventual link confirmation, HIP needs to authenticate the request with the patient(eg: OTP verification)
HIP should communicate the mode of authentication of a successful request to Consent Manager
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "19ffab9b-6450-4000-87f5-cf25dcd85019",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "19ffab9b-6450-4000-891f-c013f5571a80",
  "patient": {
    "id": "hinapatel79@ndhm",
    "referenceNumber": "TMH-PUID-001",
    "careContexts": [
      {
        "referenceNumber": "string"
      }
    ]
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/links/link/confirm
Token submission by Consent Manager for link confirmation
API to submit the token that was sent by HIP during the link request.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "19ffab9b-6450-4000-8a93-441b1c30b019",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "confirmation": {
    "linkRefNumber": "string",
    "token": "string"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
accepted

post
/v0.5/links/link/on-add-contexts
callback API for HIP initiated patient linking /link/add-context
If the accessToken is valid for purpose of linking, and specified details provided, CM will send "acknoweldgement.status" as SUCCESS. If any error occcurred, for example invalid token, or other required patient or care-context information not provided, then "error" attribute conveys so.

accessToken must be valid and must be for the purpose of linking
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "SUCCESS"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6460-4000-8c0e-c66d4cc06a80"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
accepted

post
/v0.5/links/context/on-notify
Acknowledgement sent by Consent Manager to HIP for data notification.
CM sends back acknowledgement of receiving data notification by HIPs. CM may return errors if in following scenarios:

Patient id sent by HIP in the data notification is incorrect
Carecontext sent by HIP in the data notification is not linked or incorrect.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "SUCCESS"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6460-4000-8402-b2013a0a2280"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
accepted

post
/v0.5/health-information/cm/on-request
Health information data request
Callback API for acknowledgement of Health information request of HIU. CM calls this API when it has validated the Health Information request given the consent id. Either the hiRequest or error would need to be specified. If the health info request was valid, then the hiRequest.transactionId specifies the transaction context against which HIP would send over the data. Possible cases of errors are

Invalid consent artefact id
Consent has expired
Date ranges are invalid
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "hiRequest": {
    "transactionId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "sessionStatus": "REQUESTED"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6460-4000-86fc-132f57c99019"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consent-requests/on-init
Response to consent request
Result of consent request creation for a patient. consentRequest.id represents the consentrequest id created by CM. The result must contain either consentRequest or the error caused.
Reasons for error may be

Invalid references (e.g patient id, hiu id), purpose, hiTypes, ranges, persmission
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consentRequest": {
    "id": "f29f0e59-8388-4698-9fe6-05db67aeac46"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6460-4000-81dc-b8c1d61f5501"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consent-requests/on-status
Result of consent request status
Result of consent request done previously. Status of request can be GRANTED, DENIED, EXPIRED. If the request was GRANTED, then

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consentRequest": {
    "id": "<consent-request-id>",
    "status": "GRANTED",
    "consentArtefacts": [
      {
        "id": "<consent-artefact-id>"
      }
    ]
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6460-4000-8963-2d7ddbec0401"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consents/hip/notify
Consent notification
Notification of consents to health information providers consent request granted, consent revoked, consent expired. Only the GRANTED, REVOKED and EXPIRED status notifications will be sent to HIP.

If consent is granted, status=GRANTED, then consentDetail contains the consent artefact details and signature is available.
If consent is revoked, then status=REVOKED, and consentId specifes which consent artefact is revoked.
If the consent has expired, then status=EXPIRED, and consentId specifies which consent artefact has expired. Note, this is also responsibility of the HIP to keep track of consent expiry. Any data request on expired consent artefact must not be done.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "status": "GRANTED",
    "consentId": "19ffab9b-6460-4000-8c41-de852087ca80",
    "consentDetail": {
      "schemaVersion": "",
      "consentId": "19ffab9b-6460-4000-8f16-366680ab0380",
      "createdAt": "1970-01-01T00:00:00.000Z",
      "patient": {
        "id": "hinapatel79@ndhm"
      },
      "careContexts": [
        {
          "patientReference": "hinapatel79@hospital",
          "careContextReference": "Episode1"
        }
      ],
      "purpose": {
        "text": "string",
        "code": "string",
        "refUri": "http://example.com"
      },
      "hip": {
        "id": "string"
      },
      "consentManager": {
        "id": "string"
      },
      "hiTypes": [
        "OPConsultation"
      ],
      "permission": {
        "accessMode": "VIEW",
        "dateRange": {
          "from": "1970-01-01T00:00:00.000Z",
          "to": "1970-01-01T00:00:00.000Z"
        },
        "dataEraseAt": "1970-01-01T00:00:00.000Z",
        "frequency": {
          "unit": "HOUR",
          "value": 0,
          "repeats": 0
        }
      }
    },
    "signature": "Signature of CM as defined in W3C standards; Base64 encoded"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consents/hiu/notify
Consent notification
Health information user will get notified about the consent request granted or denied, consent revoked, consent expired.

For consent request grant, status=GRANTED, consentRequestId=, and consentArtefacts is an array of generated consent artefact Ids.
For consent request expiry, status=EXPIRED, consentRequestId=
For consent request denied, status=DENIED, consentRequestId=
For consent revocation, status=REVOKED, consentArtefacts is an array of revoked consent artefact ids
REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "consentRequestId": "<consent-request-id>",
    "status": "GRANTED",
    "consentArtefacts": [
      {
        "id": "<consent-artefact-id>"
      }
    ]
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted.

post
/v0.5/consents/on-fetch
Result of fetch request for a consent artefact
Must contain either consentDetail or error. Possible reason of errors are

consentId passed through /fetch is invalid
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consent": {
    "status": "GRANTED",
    "consentDetail": {
      "schemaVersion": "",
      "consentId": "19ffab9b-6470-4000-88b4-99b4ae5bc380",
      "createdAt": "1970-01-01T00:00:00.000Z",
      "patient": {
        "id": "hinapatel79@ndhm"
      },
      "careContexts": [
        {
          "patientReference": "hinapatel79@hospital",
          "careContextReference": "Episode1"
        }
      ],
      "purpose": {
        "text": "string",
        "code": "string",
        "refUri": "http://example.com"
      },
      "hip": {
        "id": "string"
      },
      "hiu": {
        "id": "string"
      },
      "consentManager": {
        "id": "string"
      },
      "requester": {
        "name": "Dr. Manju",
        "identifier": {
          "type": "REGNO",
          "value": "MH1001",
          "system": "https://www.mciindia.org"
        }
      },
      "hiTypes": [
        "OPConsultation"
      ],
      "permission": {
        "accessMode": "VIEW",
        "dateRange": {
          "from": "1970-01-01T00:00:00.000Z",
          "to": "1970-01-01T00:00:00.000Z"
        },
        "dataEraseAt": "1970-01-01T00:00:00.000Z",
        "frequency": {
          "unit": "HOUR",
          "value": 0,
          "repeats": 0
        }
      }
    },
    "signature": "Signature of CM as defined in W3C standards; Base64 encoded"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6470-4000-880b-ca49b9c3a801"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/health-information/hip/request
Health information data request
API called by CM to request Health information from HIP against a validated consent artefact. The transactionId is the correlation id that HIP should use use when pushing data to the dataPushUrl.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "hiRequest": {
    "consent": {
      "id": "string"
    },
    "dateRange": {
      "from": "1970-01-01T00:00:00.000Z",
      "to": "1970-01-01T00:00:00.000Z"
    },
    "dataPushUrl": "string",
    "keyMaterial": {
      "cryptoAlg": "ECDH",
      "curve": "Curve25519",
      "dhPublicKey": {
        "expiry": "1970-01-01T00:00:00.000Z",
        "parameters": "Curve25519/32byte random key",
        "keyValue": ""
      },
      "nonce": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request accepted.

post
/v0.5/patients/on-find
Identification result for a consent-manager user-id
If a patient is found then patient.name contains the patients name. Otherwise, patient is not provided, and possibly error is raised for invalid requests Note in addition to the "Authorization" header, one of the following headers must be specified

specify X-HIU-ID if the requester is HIU (identified from /find requester.id)
specify X-HIP-ID if the requester is HIP (identified from /find requester.id)
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "patient": {
    "id": "hinapatel79@ndhm",
    "name": "Hina Patel"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6470-4000-8df8-489fb5a14d80"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

RESPONSE
Request Accepted

post
/v0.5/users/auth/on-fetch-modes
Identification result for a consent-manager user-id
If a patient is found then auth attribute contains the supported modes for the specified purpose. Otherwise, error is raised for invalid requests or for non-existent id. Note in addition to the "Authorization" header, one of the following headers must be specified

X-HIU-ID if the requester is HIU (identified from /auth/fetch-modes requester.id)
X-HIP-ID if the requester is HIP (identified from /auth/fetch-modes requester.id)
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "auth": {
    "purpose": "LINK",
    "modes": [
      "MOBILE_OTP"
    ]
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6470-4000-8caf-9285faef8201"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/users/auth/on-init
Response to user authentication initialization from HIP
If the patient's id is valid, CM will return a transactionId as initialization of user auth. If the request is valid, then 'auth.mode' will convey how the authentication should be done. The authentication can be mediated or direct. For mediated authentication modes, HIP or HIU is epected to send over relevant code (OTP/token) or demographic info via subsequent API call to /auth/confirm. for direct authentication case, CM will notify requester through/users/auth/notify API.

auth.mode conveys whats the mode of authentication is, and what is expected from HIP/HIU in the subsequent /auth/confirm API call. Possible values
MOBILE_OTP - auth via OTP to registered mobile. Mediated.
AADHAAR_OTP - auth initiated with Aadhaar with OTP. Mediated.
DEMOGRAPHICS - auth initiated with demographic verification
DIRECT - for authentication directly with the patient. e.g. Mobile App, SMS. In this case, the HIP/HIU is not expected to call subsequent /auth/confirm call. CM will do direct authentication with the User (e.g. Mobile App, SMS etc) and will notify requester
meta.expiry conveys the expiry time of the token and the authentication session
NOTE, only one of X-HIP-ID or X-HIU-ID will be sent as part of header, not both.
The error section in the body, represents the potential errors that may have occurred. Possible reasons:

Patient id is invalid
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "auth": {
    "transactionId": "string",
    "mode": "MOBILE_OTP",
    "meta": {
      "hint": "string",
      "expiry": "2019-12-30T12:01:55Z"
    }
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6470-4000-85e1-9a7cd26d8780"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/on-confirm
callback API for /auth/confirm (in case of MEDIATED auth) to confirm user authentication or not
This API is called by CM to confirm authentication of users.

auth.accessToken - is specific to the purpose mentioned in the /auth/init. This token needs to be used for initiating the intended action. For example for HIP initiated linking of care-contexts
NOTE, only one of X-HIP-ID or X-HIU-ID will be sent as part of header, not both.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "auth": {
    "accessToken": "string",
    "validity": {
      "purpose": "LINK",
      "requester": {
        "type": "HIP",
        "id": 100005
      },
      "expiry": "1970-01-01T00:00:00.000Z",
      "limit": "1"
    },
    "patient": {
      "id": "<patient-id>@<consent-manager-id>",
      "name": "Hina Patel",
      "gender": "M",
      "yearOfBirth": 2000,
      "address": {
        "line": "string",
        "district": "string",
        "state": "string",
        "pincode": "string"
      },
      "identifiers": [
        {
          "type": "MR",
          "value": "+919800083232"
        }
      ]
    }
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6470-4000-8c90-6d9ba13c3d01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/notify
notification API in case of DIRECT mode of authentication by the CM
This API is called by CM to confirm authentication of users. The transactionId returned is same as that passed in /auth/on-init. The "auth.status" conveys whether the request was GRANTED or DENIED.

auth.accessToken - is specific to the purpose mentioned in the /auth/init. This token needs to be used for initiating the intended action. For example for HIP initiated linking of care-contexts
NOTE, only one of X-HIP-ID or X-HIU-ID will be sent as part of header, not both.
The payload is conditional to the purpose of auth. If purpose specified in /auth/init is KYC or KYC_AND_LINK, then patient details are passed. auth.accessToken is passed only if the purpose is LINK or KYC_AND_LINK.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "auth": {
    "transactionId": "string",
    "status": "GRANTED",
    "accessToken": "string",
    "validity": {
      "purpose": "LINK",
      "requester": {
        "type": "HIP",
        "id": 100005
      },
      "expiry": "1970-01-01T00:00:00.000Z",
      "limit": "1"
    },
    "patient": {
      "id": "<patient-id>@<consent-manager-id>",
      "name": "Hina Patel",
      "gender": "M",
      "yearOfBirth": 2000,
      "address": {
        "line": "string",
        "district": "string",
        "state": "string",
        "pincode": "string"
      },
      "identifiers": [
        {
          "type": "MR",
          "value": "+919800083232"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/subscription-requests/cm/on-init
callback API for the /subscription-requests/cm/init to notify a HIU on acceptance/acknowledgement of the request for subscription.
This callback API acknowledges the request for subscription from a HIU, and sends back a "id" that will be used when the patient/user approves or denies the subscription.

REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "subscriptionRequest": {
    "id": "f29f0e59-8388-4698-9fe6-05db67aeac46"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6480-4000-8a54-55ba271b1480"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted.

post
/v0.5/subscription-requests/hiu/notify
Notification for subscription grant/deny/revoke
This API is used by CM to notify a HIU to grant or deny a request for subscription, and also to notify that in case an existing subscription is revoked or expired. For notifying that a particular subscription request was GRANTED or DENIED, the subscriptionRequestId is passed.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "subscriptionRequestId": "request id of the subscription",
    "status": "GRANTED",
    "subscription": {
      "id": "subscription Id",
      "patient": {
        "id": "hinapatel79@ndhm"
      },
      "hiu": {
        "id": "string"
      },
      "sources": [
        {
          "hip": {
            "id": "string"
          },
          "categories": [
            "LINK"
          ],
          "period": {
            "from": "1970-01-01T00:00:00.000Z",
            "to": "1970-01-01T00:00:00.000Z"
          }
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/subscriptions/hiu/notify
Notification to HIU on basis of a granted subscription
This API is used by CM to notify a HIU for notification relevant to subscription. Notifications are sent to subscribed HIUs whenever a new care-context is linked or new data is available on an existing linked care-context.

if event.category = LINK, then only care-contexts are passed when new care-contexts are linked for patient.
If event.category = DATA, then hiTypes are passed. Care-context is passed only if the subscribed HIU has any valid consent for that care-context
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "event": {
    "id": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "published": "1970-01-01T00:00:00.000Z",
    "subscriptionId": "subscription Id",
    "category": "LINK",
    "content": {
      "patient": {
        "id": "hinapatel79@ndhm"
      },
      "hip": {
        "id": "string"
      },
      "context": [
        {
          "careContext": {
            "patientReference": "batman@tmh",
            "careContextReference": "Episode1"
          },
          "hiTypes": [
            "OPConsultation"
          ]
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/patients/sms/on-notify
Acknowledgment response for SMS notification sent to patient by HIP
If the SMS notification is successfully sent to patient then "status" will be "ACKNOWLEDGED" with no error. If the SMS notification is failed then "status" will be "ERRORED" with error.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "status": "ACKNOWLEDGED",
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6480-4000-8bd7-33b8665a1f01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/patients/status/notify
Notification sent by Consent MAnager
Status (ACTIVE/DEACTIVATED/DELETED) will be sent to HIP. Note in addition to the "Authorization" header, one of the following headers must be specified

X-HIU-ID if the requester is HIU .
X-HIP-ID if the requester is HIP .
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "status": "DEACTIVATED",
    "patient": {
      "id": "hina@ncg"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/health-information/cm/request
Health information data request
Request for Health information against a consent id. CM would generate a transactionId against each consent and pass it as trnasaction context / correlation id to the HIP and also return the same to HIU via /on-request.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "hiRequest": {
    "consent": {
      "id": "string"
    },
    "dateRange": {
      "from": "1970-01-01T00:00:00.000Z",
      "to": "1970-01-01T00:00:00.000Z"
    },
    "dataPushUrl": "string",
    "keyMaterial": {
      "cryptoAlg": "ECDH",
      "curve": "Curve25519",
      "dhPublicKey": {
        "expiry": "1970-01-01T00:00:00.000Z",
        "parameters": "Curve25519/32byte random key",
        "keyValue": ""
      },
      "nonce": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consent-requests/init
Create consent request
Creates a consent request to get data about a patient by HIU user.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consent": {
    "purpose": {
      "text": "string",
      "code": "string",
      "refUri": "http://example.com"
    },
    "patient": {
      "id": "hinapatel79@ndhm"
    },
    "hip": {
      "id": "string"
    },
    "careContexts": [
      {
        "patientReference": "batman@tmh",
        "careContextReference": "Episode1"
      }
    ],
    "hiu": {
      "id": "string"
    },
    "requester": {
      "name": "Dr. Manju",
      "identifier": {
        "type": "REGNO",
        "value": "MH1001",
        "system": "https://www.mciindia.org"
      }
    },
    "hiTypes": [
      "OPConsultation"
    ],
    "permission": {
      "accessMode": "VIEW",
      "dateRange": {
        "from": "1970-01-01T00:00:00.000Z",
        "to": "1970-01-01T00:00:00.000Z"
      },
      "dataEraseAt": "1970-01-01T00:00:00.000Z",
      "frequency": {
        "unit": "HOUR",
        "value": 0,
        "repeats": 0
      }
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consent-requests/status
Get consent request status
Get status of consent request done previously

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consentRequestId": "string"
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/consents/hiu/on-notify
Consent notification
This API is called by HIU as acknowledgement to consent notifications, specifically for cases when consent is REVOKED or EXPIRED.

REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": [
    {
      "status": "OK",
      "consentId": "<consent-artefact-id>"
    }
  ],
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-6490-4000-830f-bd6febd27019"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted.

post
/v0.5/consents/fetch
Get consent artefact
REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "consentId": "string"
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/health-information/notify
Notifications corresponding to events during data flow
API called by HIU and HIP during data-transfer.

HIP on transfer of data would send sessionStatus - one of [TRANSFERRED, FAILED]
HIP would also send hiStatus for each careContextReference - on of [DELIVERED, ERRORED]
HIU on receipt of data would send sessionStatus - one of [TRANSFERRED, FAILED]. For example, FAILED when if data was not sent or if invalid data was sent
HIU would also send hiStatus for each careContextReference - one of [OK, ERRORED]
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "consentId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "transactionId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "doneAt": "1970-01-01T00:00:00.000Z",
    "notifier": {
      "type": "HIU",
      "id": "tmh"
    },
    "statusNotification": {
      "sessionStatus": "TRANSFERRED",
      "hipId": "max",
      "statusResponses": [
        {
          "careContextReference": "string",
          "hiStatus": "OK",
          "description": "string"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Notification is Accepted

post
/v0.5/patients/find
Identify a patient by her consent-manager user-id
This API is meant for identify to patient given her consent-manager-user-id

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "query": {
    "patient": {
      "id": "hinapatel79@ndhm"
    },
    "requester": {
      "type": "HIU",
      "id": 100005
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/users/auth/fetch-modes
Get a patient's authentication modes relevant to specified purpose
This API is meant for identify supported authentication modes for a patient given a specific purpose

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "query": {
    "id": "hinapatel79@ndhm",
    "purpose": "LINK",
    "requester": {
      "type": "HIP",
      "id": "100005"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/users/auth/init
Initialize authentication from HIP
This API is called by HIPs to initiate authentication of users. A transactionId is retuned by the corresponding callback API for confirmation of user auth.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "query": {
    "id": "hinapatel@ndhm",
    "purpose": "LINK",
    "authMode": "MOBILE_OTP",
    "requester": {
      "type": "HIP",
      "id": 100005
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/confirm
Confirmation request sending token, otp or other authentication details from HIP/HIU for confirmation
This API is called by HIP/HIUs to confirm authentication of users. The transactionId returned by the previous callback API /users/auth/on-init must be sent. If Authentication is successful the callback API will send an "access token" for subsequent purpose specific API calls. Note only credential.authCode or credential.demographic should be sent

demographic details are only required for demographic auth as of now.
demographic details are required only in MEDIATED cases and if the auth.mode so demands. e.g. if auth.mode is DEMOGRAPHICS. Usually for demographic authentication, the name, gender and DOB must be exactly as specified in User Account.
demographic.identifier is optional, however maybe required if authentication so mandates.
credential.authCode is required for other MEDIATED authentication like MOBILE_OTP, AADHAAR_OTP.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "string",
  "credential": {
    "authCode": "string",
    "demographic": {
      "name": "janki das",
      "gender": "M",
      "dateOfBirth": "1972-02-29",
      "identifier": {
        "type": "MOBILE",
        "value": "+919800083232"
      }
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/on-notify
callback API by HIU/HIPs as acknowledgement of auth notification
This API is called by HIU/HIPs to confirm acknowledgement for receipt of auth notification is case of DIRECT authentication.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "OK"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64a0-4000-82ad-4389cd203301"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/subscription-requests/cm/init
Request for subscription
creates a request for subscription. The subscription categories can be for care-contexts linkages or availability of data against existing care-contexts. Note that the requester must have HIU role

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "subscription": {
    "purpose": {
      "text": "string",
      "code": "string",
      "refUri": "http://example.com"
    },
    "patient": {
      "id": "hinapatel79@ndhm"
    },
    "hiu": {
      "id": "string"
    },
    "hips": [
      {
        "id": "string"
      }
    ],
    "categories": [
      "LINK"
    ],
    "period": {
      "from": "1970-01-01T00:00:00.000Z",
      "to": "1970-01-01T00:00:00.000Z"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/subscription-requests/hiu/on-notify
Callback API for /subscription-requests/hiu/notify to acknowledge receipt of notification.
This API is called by HIU as acknowledgement to subscription request relevant notifications.

REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "OK",
    "subscriptionRequestId": "subscription Id"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64a0-4000-8796-cefae9d6cc80"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted.

post
/v0.5/subscriptions/hiu/on-notify
Callback API for /subscriptions/hiu/notify to acknowledge receipt of notification.
This API is called by HIU as acknowledgement to consent notifications, specifically for cases when consent is REVOKED or EXPIRED.

REQUEST
REQUEST BODY
*
application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "OK",
    "eventId": "subscription event Id"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64a0-4000-8190-d45b87ca7c01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted.

post
/v0.5/patients/status/on-notify
Acknowledgment by HIP/HIU
This API is to be called by HIU/HIP bridge after receiving patient status (Activation/Reactivation/Deletion). In case of successfully receiving the notification "status" should sent as "OK" with no error.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgment": {
    "status": "OK"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64a0-4000-8f8a-3a26ad53e401"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/links/link/on-init
Response to patient's care context link request
Result of patient care-context link request from HIP end. This happens in context of previous discovery of patient found at HIP end, therefore the link requests ought to be in reference to the patient reference and care-context references previously returned by the HIP. The correlation of discovery and link request is maintained through the transactionId. HIP should have

Validated transactionId in the request to check whether there was a discovery done previously, and the link request corresponds to returned patient care care context references
Before returning the response, HIP should have sent an authentication request to the patient(eg: OTP verification)
HIP should communicate the mode of authentication of a successful request
HIP subsequently should expect the token passed via /link/confirm against the link.referenceNumber passed in this call
The error section in the body, represents the potential errors that may have occurred. Possible reasons:

Patient reference number is invalid
Care context reference numbers are invalid
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
  "link": {
    "referenceNumber": "string",
    "authenticationType": "DIRECT",
    "meta": {
      "communicationMedium": "MOBILE",
      "communicationHint": "string",
      "communicationExpiry": "2019-12-30T12:01:55Z"
    }
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64a0-4000-81de-d486b0b7a680"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/links/link/on-confirm
Token authenticated by HIP, indicating completion of linkage of care-contexts
Returns a list of linked care contexts with patient reference number.

Validated and linked account reference number
Validated that the token sent from Consent Manager is same as the one generated by HIP
Verified that same Consent Manager which made the link request is sending the token
Results of unmasked linked care contexts with patient reference number
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "patient": {
    "referenceNumber": "string",
    "display": "string",
    "careContexts": [
      {
        "referenceNumber": "string",
        "display": "string"
      }
    ]
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64a0-4000-8993-300b2700c201"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
accepted

post
/v0.5/links/link/add-contexts
API for HIP initiated care-context linking for patient
API to submit care-context to CM for HIP initiated linking. The API must accompany the "accessToken" fetched in the users/auth process.

subsequent usage for accessToken may be invalid if it was meant for one-time usage or if it expired
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "link": {
    "accessToken": "string",
    "patient": {
      "referenceNumber": "TMH-PUID-001",
      "display": "string",
      "careContexts": [
        {
          "referenceNumber": "string",
          "display": "string"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
accepted

post
/v0.5/links/context/notify
This API is meant to be called by HIPs when there is new health data generated for a patient, against a care context that is already linked to patient's ABDM account.
This API is called by HIP only when there is new health data is added/created for a patient and under a care context that is already linked with patient's Health Account. HIP can send following things in this API to notify the Consent Manager about the new health data added:

Patient's Identifier for which the new health data is added (It can be ABDM id or ABDM number)
Care Context reference under which the new health data is added
Patient's reference (An identifier with which the patient is registered on HIP)
Types of health information documents that have been added
A date when the health information was created/added on the HIP Note: This API shouldn't be called if the new heath data of is added/created under new care context.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "patient": {
      "id": "hinapatel@ncg"
    },
    "careContext": {
      "patientReference": "batman@tmh",
      "careContextReference": "Episode1"
    },
    "hiTypes": [
      "OPConsultation"
    ],
    "date": "1970-01-01T00:00:00.000Z",
    "hip": {
      "id": 1000010
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
accepted

post
/v0.5/consents/hip/on-notify
Consent notification
This API is called by HIP as acknowledgement to notification of consents, in cases of consent revocation and expiration.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "OK",
    "consentId": "<consent-artefact-id>"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64b0-4000-8e1b-637937810e80"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/health-information/notify
Notifications corresponding to events during data flow
API called by HIU and HIP during data-transfer.

HIP on transfer of data would send sessionStatus - one of [TRANSFERRED, FAILED]
HIP would also send hiStatus for each careContextReference - on of [DELIVERED, ERRORED]
HIU on receipt of data would send sessionStatus - one of [TRANSFERRED, FAILED]. For example, FAILED when if data was not sent or if invalid data was sent
HIU would also send hiStatus for each careContextReference - one of [OK, ERRORED]
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "499a5a4a-7dda-4f20-9b67-e24589627061",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "consentId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "transactionId": "a1s2c932-2f70-3ds3-a3b5-2sfd46b12a18d",
    "doneAt": "1970-01-01T00:00:00.000Z",
    "notifier": {
      "type": "HIU",
      "id": "tmh"
    },
    "statusNotification": {
      "sessionStatus": "TRANSFERRED",
      "hipId": "max",
      "statusResponses": [
        {
          "careContextReference": "string",
          "hiStatus": "OK",
          "description": "string"
        }
      ]
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Notification is Accepted

post
/v0.5/health-information/hip/on-request
Health information data request
API called by HIP to acknowledge Health information request receipt. Either the hiRequest or error must be specified. hiRequest element returns the same transactionId as before with a status indicating that the request is acknowledged.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "hiRequest": {
    "transactionId": "19ffab9b-64b0-4000-89c9-a8e90bbbf301",
    "sessionStatus": "ACKNOWLEDGED"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64b0-4000-807d-a8a09caa6301"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted.

post
/v0.5/users/auth/fetch-modes
Get a patient's authentication modes relevant to specified purpose
This API is meant for identify supported authentication modes for a patient given a specific purpose

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "query": {
    "id": "hinapatel79@ndhm",
    "purpose": "LINK",
    "requester": {
      "type": "HIP",
      "id": "100005"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/users/auth/init
Initialize authentication from HIP
This API is called by HIPs to initiate authentication of users. A transactionId is retuned by the corresponding callback API for confirmation of user auth.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "query": {
    "id": "hinapatel@ndhm",
    "purpose": "LINK",
    "authMode": "MOBILE_OTP",
    "requester": {
      "type": "HIP",
      "id": 100005
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/confirm
Confirmation request sending token, otp or other authentication details from HIP/HIU for confirmation
This API is called by HIP/HIUs to confirm authentication of users. The transactionId returned by the previous callback API /users/auth/on-init must be sent. If Authentication is successful the callback API will send an "access token" for subsequent purpose specific API calls. Note only credential.authCode or credential.demographic should be sent

demographic details are only required for demographic auth as of now.
demographic details are required only in MEDIATED cases and if the auth.mode so demands. e.g. if auth.mode is DEMOGRAPHICS. Usually for demographic authentication, the name, gender and DOB must be exactly as specified in User Account.
demographic.identifier is optional, however maybe required if authentication so mandates.
credential.authCode is required for other MEDIATED authentication like MOBILE_OTP, AADHAAR_OTP.
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "transactionId": "string",
  "credential": {
    "authCode": "string",
    "demographic": {
      "name": "janki das",
      "gender": "M",
      "dateOfBirth": "1972-02-29",
      "identifier": {
        "type": "MOBILE",
        "value": "+919800083232"
      }
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/users/auth/on-notify
callback API by HIU/HIPs as acknowledgement of auth notification
This API is called by HIU/HIPs to confirm acknowledgement for receipt of auth notification is case of DIRECT authentication.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgement": {
    "status": "OK"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64c0-4000-8656-a8c290012a01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request accepted

post
/v0.5/patients/sms/notify
deprecated
API for HIP to send SMS notifications to patients
API to send SMS notifications to patient with custom deeplink.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "phoneNo": "+91-9999999999",
    "receiverName": "Ramesh Singh (Optional)",
    "careContextInfo": "X-Ray on 22nd Dec",
    "deeplinkUrl": "https://link.to.health.records/ (Optional)",
    "hip": {
      "name": "Max Healthcare (Optional)",
      "id": "HIP_001"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
accepted

post
/v0.5/patients/status/on-notify
Acknowledgment by HIP/HIU
This API is to be called by HIU/HIP bridge after receiving patient status (Activation/Reactivation/Deletion). In case of successfully receiving the notification "status" should sent as "OK" with no error.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgment": {
    "status": "OK"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64c0-4000-8861-f8571d931a01"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/patients/sms/notify
deprecated
API for HIP to send SMS notifications to patients
API to send SMS notifications to patient with custom deeplink.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "phoneNo": "+91-9999999999",
    "receiverName": "Ramesh Singh (Optional)",
    "careContextInfo": "X-Ray on 22nd Dec",
    "deeplinkUrl": "https://link.to.health.records/ (Optional)",
    "hip": {
      "name": "Max Healthcare (Optional)",
      "id": "HIP_001"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
accepted

post
/v0.5/patients/sms/on-notify
Acknowledgment response for SMS notification sent to patient by HIP
If the SMS notification is successfully sent to patient then "status" will be "ACKNOWLEDGED" with no error. If the SMS notification is failed then "status" will be "ERRORED" with error.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "status": "ACKNOWLEDGED",
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64c0-4000-835b-8c14a78f2901"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/patients/status/on-notify
Acknowledgment by HIP/HIU
This API is to be called by HIU/HIP bridge after receiving patient status (Activation/Reactivation/Deletion). In case of successfully receiving the notification "status" should sent as "OK" with no error.

REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "acknowledgment": {
    "status": "OK"
  },
  "error": {
    "code": 0,
    "message": "string"
  },
  "resp": {
    "requestId": "19ffab9b-64c0-4000-8ed1-4f40f4373501"
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-CM-ID
string
Suffix of the consent manager to which the request was intended.

RESPONSE
Request Accepted

post
/v0.5/patients/status/notify
Notification sent by Consent MAnager
Status (ACTIVE/DEACTIVATED/DELETED) will be sent to HIP. Note in addition to the "Authorization" header, one of the following headers must be specified

X-HIU-ID if the requester is HIU .
X-HIP-ID if the requester is HIP .
REQUEST
REQUEST BODY
*
application/json

application/json
EXAMPLE
SCHEMA
{
  "requestId": "5f7a535d-a3fd-416b-b069-c97d021fbacd",
  "timestamp": "1970-01-01T00:00:00.000Z",
  "notification": {
    "status": "DEACTIVATED",
    "patient": {
      "id": "hina@ncg"
    }
  }
}
REQUEST HEADERS
* Authorization
string
Access token which was issued after successful login with gateway auth server.

* X-HIP-ID
string
Identifier of the health information provider to which the request was intended.

* X-HIU-ID
string
Identifier of the health information user to which the request was intended.

RESPONSE
Request Accepted

Create Gateway Session Token

Server : https://dev.abdm.gov.in/gateway

Verify using Postman

We recommened you get comfortable using POSTMAN to check various ABDM APIs like the sessions API above. Setup Postman, download the ABDM API collection and use POSTMAN to verify your client id and secret is able to generate a session token.

Check your JWT token

You can use jwt.io to see the contents of your gateway session token (accessToken). Paste the accessToken and see what roles have been assigned to your client id. Some ABDM APIs requires your clientID to have specific roles to assigned. You can mail to Integration.support@nha.gov.in to get specific roles added.

jwt-io.png

