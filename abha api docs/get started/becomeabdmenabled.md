Become ABDM Enabled
To become an ABDM Compliant Application, your application needs to implement specific functional capabilities and pass functional validation.

The functionality has been broken into milestones to make it easier to implement. Your first step is to understand the functionalities that your application must support. This depends on what type of application you are integrating.

Certification Functionality to be implemented	M1	M2	M3	Locker	Integrated Programs	HFR	HPR
HMIS Applications - Used by Hospitals	Yes	Yes	Yes	NA	NA	Yes	NA
LMIS Application - Used by Labs	Yes	Yes	NA	NA	NA	Yes	NA
PHR Application - For consumers	Yes	Yes	Yes	Yes	NA	NA	NA
Public Health Application - Used by Health Workers	Yes	Yes	Yes	No	Yes	Yes	NA
Teleconsultation Application	Yes	Yes	Yes	No	No	Yes	Yes
Each functionality is defined by detailed test cases that must be passed to to obtain ABDM compliance.

Here is a quick list of functionalities that must be implemented for each milestone certification:

Features to be supported for Milestone 1 (M1)
Create ABHA number & address - using Aadhaar / Mobile / Driving License
Collect & Verify ABHA address during patient registration
Features to be supported for Milestone 2 (M2)
Description: This milestone enables a health care entity/professional to become Health Information Provider (HIP). This implies that the clinical artifacts generated will be linked with the ABHA address and is ready for consent-based-sharing within ABDM eco-system. Patient will be able to view the generated and linked health records in any of his/her preferred PHR app downloaded in the mobile device. Functionalities offered are:

Help users discover their health records available in your system
Notify or link a new health record with an ABHA address when it is created in your system
Share the health record on request after verifying user consent
Structure the health record in an inter-operable format using FHIR
Support Consent revokes, deletion of ABHA address and uptime related heartbeats
Features to be supported for Milestone 3 (M3)
Description: Completion of this milestone enables a healthcare entity/professional to become Health Information User (HIU). This milestone enables exchange of health data between healthcare entities or healthcare professionals or with a health locker based on patient authentication only. Any entity seeking health records for treatment/diagnosis is termed as Health Information User (HIU). Functionalities offered are:

Request access to health records linked with a ABHA address
Fetch linked records from HRPs
Organize and display fetched health records
Features to be supported by a PHR app
Issue ABHA address to the user
Link the ABHA address to a ABHA No if requested by the user
Login (authenticate) the user
Share user profile during registration at a healthcare provider
Discover user records at a healthcare provider
Link health records discovered by the user to their ABHA address
Notify users when any new health records are linked to their ABHA address
Raise consent requests and fetch newly linked records (Milestone 3)
Organize and display fetched health records
Help the user manage their consents & subscriptions
Scan / Upload records held by the user and link it to their PHR address
Act as a HRP for users health records and share it on demand
Help users edit their profile, delete ABHA address
Features to be supported by Integrated programs
Goverment Health programs need to mandatorily support the following additional features apart from any of the above roles

Issue an unique ABHA No to the user using offline methods
Create a linked ABHA address
Link health records to ABHA No
Fetch health records from ABHA No