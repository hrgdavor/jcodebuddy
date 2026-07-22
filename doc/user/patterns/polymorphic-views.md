# Polymorphic View Patterns

## Goal

Represent a family of related entity views with a shared base interface and type-specific concrete views.

## When to use

Use polymorphic views when a common entity type has several concrete variants with different fields.

## Example

```java
public interface PaymentMethod extends EntityBase<String> {
    String id();
    String type();
    String transactionId();
    BigDecimal amount();
}

public interface CreditCardPaymentMethod extends PaymentMethod {
    String maskedCardNumber();
    String expiryDate();
}

public interface PayPalPaymentMethod extends PaymentMethod {
    String paypalEmail();
    String payerId();
}
```

## What happens under the hood

- The base interface `PaymentMethod` defines fields shared across all variants.
- Each concrete view adds its own variant-specific fields.
- Generated metadata enums such as `CreditCardPaymentMethod_` allow generic serializers and deserializers to work with each variant.

## Why this helps

- It avoids duplicate field definitions.
- It keeps shared fields in one place.
- It makes it easy to build transports or APIs that accept different concrete variants.

## See also

- [Getting Started](../getting-started.md)
- [Core Concepts](../core-concepts.md)
