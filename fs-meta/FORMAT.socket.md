# format for unix socket communication

notes

- define binary handshake but in such way it can not happen first two bytes are start of JSON object `{"`
  - this way we easily recognize when it is JSONRPC 
- binary wire protocol is 8 bytes for message prefix and 
  - when sending changes adds 64 bytes from meta followed by file name without newline
  - those 64 bytes have file name length that makes it easy to know that entry being read is 64+filenameLength and next starts after it 
  - commands are encoded in the 8 bytes somehow
  - START_BATCH
  - END_BATCH
  - CHANGE_ENTRY
  - etc...

