WORKING WITH ABDM APIS
The Authorization Header
Almost all ABDM APIs require you to pass the session token obtained via your client ID / secret in the Authorization HTTP Header. Remember to add the word “Bearer " in front of the JWT access Token you obtain from the https://dev.abdm.gov.in/gateway/v0.5/sessions


Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6IC.....
ABDM Client IDs & Call Back URLs
Every organization that completes certification with ABDM is issued one or more Client IDs
Each Client ID should be used by the organization to register ONE INSTANCE of their ABDM compiliant software
One Client ID can be used to register only ONE callback URL.
Each instance of the software can hold health records for one or more health facilities
Each Health Facility must register in the Health Facility Registry and obtain a HFR ID. This ID also acts as the HIP ID in the ABDM APIs.
The HFR login is used by the facility to register the Client ID / Callback URL of the ABDM compliant software being used.
Facility id must be passed in X-HIP-ID Header in ABDM apis
clientid-callback - Copy.png

The HIE-CM APIs are designed to be asyncronous. When you call an API on the gateway, if the request is accepted you will merely get a HTTP 200 response acknowledging that the request has been recorded.

Once the response is available, the gateway will invoke the callback URL registered for the Client ID. All integrators must register a call back URL for their Client ID

The Sequence diagram below provides an example of the asyncronous nature of the ABDM APIs.

abdm-api5.png

Note : Use ISO timestamps for current timestamps while working with apis in postman (For example: 2023-05-09T21:10:36.177Z)

Registering your callback URL (Sandbox Only)
The callback URL is the url for your site where your application recieves APIs calls from the ABDM gateway. After obtaining the client ID and the secret, use the gateway sessions api to get the accessToken. Use the below api, to register the callback url. Pass the Gateway Session Token in the “Authorization:” header and the callback url in “url:”.

https://sandbox.abdm.gov.in/Yaml/ndhm-devservice.yml ┃ 503
Disclaimer This API is only available in in sandbox. This is configured via Health Facility Registry in production

After registering the callback url, if we fire any API which has a callback, ABDM will post the response of the fired API to that callback url. You can use the /v1/bridge/getServices API to check what is configured for your client ID including the callback URL.

Linking the HIPs / HIUs ID for a Client ID
The HIP code used by ABDM is the same as the health facility registry ID.

In the Sandbox you can simply declare a HIP/HIU ID without it being part of the health facility registry. You can use it to link one HIP / HIU ID with your Client ID with no validations.

Note Make sure you provide a Unique HIP ID and Facility name (Make up a unique id and name). The Sandbox is a shared integration system and currently does not check for duplicate HIP IDs. This will make it easier for you to search and discover your health facility in the PHR app

Check your configuration
For detailed APIs related to registration with the ABDM gateway

https://sandbox.abdm.gov.in/swagger/ndhm-devservice.yaml

ABDM HTTP Headers
A single ABDM Client (also called a Bridge) can support multiple HIPs / HIUs on the same registered URL. In order to clearly identify which HIP / HIU this message was intented to be sent, the Gateway adds the following headers as part its API calls

The X-HIP-ID Header – Contains the health facility registry code that is linked to the Client ID
The X-HIU-ID Header – Contains the health information user code that is linked to this Client ID
The ABDM architecture is designed to support multiple HIE-CMs. Whenever you call any Gateway API you must include a header that tells the gateway which HIE-CM must this request be sent to

X-CM-ID – Pass ‘sbx’ if you are in the sandbox, Pass ‘abdm’ if you are in production. You must get this from HIE-CM domain name suffixed after the @ symbol in a PHR address.
Use Webhook to learn APIs
We recommend using a combination of Postman & webhook to quickly understand ABDM APIs. Use your postman collection and ensure your async callbacks are working in sandbox

Go to https://webhook.site
Copy your unique URL (which would be your callback url) shown on the page
Register it with ABDM Gateway. Ensure you get a Response: 200
Link a HIP / HIU ID of your choice to your client id. Ensure you get a Response: 200
Create a Sandbox ABHA address at https://phrsbx.abdm.gov.in
Call an async Gateway API like /v0.5/users/auth/fetch-modes with the @sbx ABHA address
Verify you get a callback (like /v0.5/users/auth/on-fetch-modes) at your webhook site. It should look like the image below
webhook-site-demo.png

Sample User Experience for Callback
img1.png

Img1

img2.png

Img2

img3.png

Img3

img4 .png

Img4

img5.png

Img5

img6.png

Img6

img7.png

Img7