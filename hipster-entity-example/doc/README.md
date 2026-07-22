# hipster-entity-example Payment Method Sample

This document describes the payment method example in `hipster-entity-example`.
It demonstrates a realistic polymorphic entity definition with metadata-backed view support for serialization and deserialization.

## Goals

- Define a base payment method entity with shared transaction fields.
- Define concrete payment method views for different payment types.
- Use metadata-backed field definitions to support generic serialization/deserialization.
- Show how a polymorphic view family can be represented by interface inheritance.

## Package structure

- `hr.hrg.hipster.entity.paymentMethod`
  - `PaymentMethod.java`
  - `CreditCardPaymentMethod.java`
  - `PayPalPaymentMethod.java`
  - `BankTransferPaymentMethod.java`
  - `CryptoPaymentMethod.java`
  - `PaymentMethod_.java`
  - `CreditCardPaymentMethod_.java`
  - `PayPalPaymentMethod_.java`
  - `BankTransferPaymentMethod_.java`
  - `CryptoPaymentMethod_.java`

## What the example shows

### Base payment method interface

`PaymentMethod` defines common fields for every payment:

- `id`
- `type` (discriminator)
- `transactionId`
- `amount`
- `currency`
- `timestamp`
- `status`

Each concrete payment view extends `PaymentMethod` and adds its own data.

### Concrete payment views

- `CreditCardPaymentMethod`
  - `maskedCardNumber`
  - `expiryDate`
  - `cardType`
  - `gatewayTransactionId`
- `PayPalPaymentMethod`
  - `paypalEmail`
  - `payerId`
  - `payerStatus`
- `BankTransferPaymentMethod`
  - `accountNumber`
  - `routingNumber`
  - `bankName`
  - `swiftCode`
- `CryptoPaymentMethod`
  - `walletAddress`
  - `transactionHash`
  - `network`

### Metadata-backed views

Each view has a companion enum ending with `_` that provides:

- field names that match the interface accessors
- field type metadata
- a `forName(String)` lookup method
- a `ViewMeta` instance for generic read/serialization support

This matches the existing `hipster-entity` metadata convention while using an underscore suffix instead of `Field`.

## Serialization / deserialization

The metadata enums are designed to make generic view serialization and deserialization possible without reflection-heavy per-field mappings.

- `ViewMeta` captures the view type and field definitions.
- `ArrayBackedViewProxyFactory` creates view instances from positional field arrays.
- `EntityReadArray` and `EntityUpdateTrackingArray` support reading and update-aware write views.

## Why this is polymorphic

The payment method example is polymorphic because the concrete payment views share a base interface (`PaymentMethod`) but differ by concrete fields and a `type` discriminator.

This is useful for:

- generic JDBC/data transport layers
- JSON serialization frameworks
- RPC DTOs that need to carry a discriminator plus concrete payload

## Notes

- The example uses `@View(read = TRUE, write = FALSE)` on concrete views.
- Concrete views inherit the shared base fields and add type-specific fields.
- The `type` discriminator is defined on the base view so a serializer/deserializer can identify the correct concrete view class.
