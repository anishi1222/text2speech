# text2speech

Text to Speech Sample Application
This sample application uses Azure Cognitivie Service (Speech Service) and Azure Functions to create audio file containing speech in English.

## Prerequisites

- Create Speech Service instance, following https://docs.microsoft.com/azure/cognitive-services/speech-service/overview.
- (Optional) Create a storage account for Blob storage. You can use the same storage account for Function app you creates.
- Create two Blob containers, each name is "in-files", and "out-files".
- Build the sample application and deploy it.

## Usage

1. Create text file(s) in English and upload them to "in-files" blob container.
2. After processing them, you can download mp3 files from "out-files" blob container.
