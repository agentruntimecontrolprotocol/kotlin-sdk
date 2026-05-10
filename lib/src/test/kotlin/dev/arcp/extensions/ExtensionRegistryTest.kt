package dev.arcp.extensions

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ExtensionRegistryTest :
    StringSpec({
        "valid arcpx names are accepted" {
            ExtensionRegistry.isValidName("arcpx.acme.cache.v1") shouldBe true
            ExtensionRegistry.isValidName("arcpx.example-co.thing.v2") shouldBe true
        }

        "valid reverse-DNS names are accepted" {
            ExtensionRegistry.isValidName("com.acme.workflow.v2") shouldBe true
            ExtensionRegistry.isValidName("io.arcp.thing.thing.v1") shouldBe true
        }

        "x- prefix is rejected (reserved for transport-internal fields)" {
            ExtensionRegistry.isValidName("x-experimental") shouldBe false
            ExtensionRegistry.isValidName("x-arcpx.acme.cache.v1") shouldBe false
        }

        "names without v<n> suffix are rejected" {
            ExtensionRegistry.isValidName("arcpx.acme.cache") shouldBe false
            ExtensionRegistry.isValidName("com.acme.thing") shouldBe false
        }

        "advertise validates names" {
            val r = ExtensionRegistry()
            r.advertise("arcpx.acme.cache.v1")
            r.advertised shouldBe setOf("arcpx.acme.cache.v1")
            shouldThrow<IllegalArgumentException> { r.advertise("not.valid") }
        }

        "unknown core-shaped types nack regardless of optional flag" {
            classifyUnknown("session.zzz", optional = true, advertisedExtensions = emptySet())
                .shouldBeInstanceOf<UnknownAction.Nack>()
        }

        "namespaced unknown extension drops only when optional and not advertised" {
            classifyUnknown("arcpx.acme.cache.v1.invalidate", optional = true, advertisedExtensions = emptySet())
                .shouldBeInstanceOf<UnknownAction.Drop>()
            classifyUnknown("arcpx.acme.cache.v1.invalidate", optional = false, advertisedExtensions = emptySet())
                .shouldBeInstanceOf<UnknownAction.Nack>()
        }
    })
