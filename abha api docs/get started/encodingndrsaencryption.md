ENCODING & RSA ENCRYPTION
Overview

Several API parameters require encoding / encryption. This section points you to tools that are useful when testing APIs using POSTMAN

Convert Image to Base64
RSA Encryption
Click here for sample RSA encryption code in JAVA
Convert Image to Base64
Converting an image into Base64 string means taking the binary representation of an image and converting it into a string of ASCII digits (A-Z, a-z, 0-9).

To convert an image into Base64 :

use https://codebeautify.org/image-to-base64-converter
After uploading the image and converting into Base64 string, copy the string and use it in the request body of API which expects it.
Sample User Experience for Converting Image to Base64
convert_image_to_base64.gif

RSA Encryption
RSA(Rivest-Shamir-Adleman) is an Asymmetric encryption technique that uses two different keys as public and private keys to perform the encryption and decryption. With RSA, you can encrypt sensitive information with a public key and a matching private key is used to decrypt the encrypted message.

To encrypt the data :

Step-1: API to retrieve public key
Step-2: RSA Encryption via online
RSA Encryption

API Type	Certificate URL	Cipher Type
For V3 APIs	https://healthidsbx.abdm.gov.in/api/v1/auth/cert	RSA/ECB/OAEPWithSHA-1AndMGF1Padding
For PHR V1 APIs	https://phrsbx.abdm.gov.in/api/v1/phr/public/certificate	RSA/ECB/PKCS1Padding
For V1 APIs	https://healthidsbx.abdm.gov.in/api/v1/auth/cert	RSA/ECB/PKCS1Padding
For V2 APIs	https://healthidsbx.abdm.gov.in/api/v2/auth/cert	RSA/ECB/PKCS1Padding
 

RSA Encryption For V3 APIs
API to retrieve the public key
Authentication token public certificate. This certificate is also used to encrypt the data.

retrieve_public_keey_api.png

RSA Encryption via online (while using Postman)
https://www.devglan.com/online-tools/rsa-encryption-decryption

Select cipher type - RSA/ECB/OAEPWithSHA-1AndMGF1Padding

cipher_type56.png

After encrypting, copy the string and use it in the response body of API which expects it.

RSA Encryption For PHR V1 APIs
API to retrieve the public key
v2_api.png

BASE URL: https://phrsbx.abdm.gov.in/

 

RSA Encryption via online (while using Postman)
https://www.devglan.com/online-tools/rsa-encryption-decryption

Select cipher type - RSA/ECB/PKCS1Padding

cipher_type_others.png

After encrypting, copy the string and use it in the response body of API which expects it.

RSA Encryption For V1 APIs
API to retrieve the public key
v1_api.png

BASE URL: https://healthidsbx.abdm.gov.in/api

 

 

RSA Encryption via online (while using Postman)
https://www.devglan.com/online-tools/rsa-encryption-decryption

Select cipher type - RSA/ECB/PKCS1Padding

cipher_type_others.png

After encrypting, copy the string and use it in the response body of API which expects it.

RSA Encryption For V2 APIs
API to retrieve the public key
v2_api.png

BASE URL: https://healthidsbx.abdm.gov.in/api

RSA Encryption via online (while using Postman)
https://www.devglan.com/online-tools/rsa-encryption-decryption

Select cipher type - RSA/ECB/PKCS1Padding

cipher_type_others.png

After encrypting, copy the string and use it in the response body of API which expects it.

Sample User Experience for RSA Encryption
rsa_encryption.gif