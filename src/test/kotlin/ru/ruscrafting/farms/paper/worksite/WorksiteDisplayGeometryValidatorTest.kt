package ru.ruscrafting.farms.paper.worksite

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.ruscrafting.farms.paper.worksite.WorksiteDisplayGeometryValidator.Box
import kotlin.math.PI

class WorksiteDisplayGeometryValidatorTest : FunSpec({
    test("invalid dimensions cannot silently escape the audit") {
        shouldThrow<IllegalArgumentException> {
            WorksiteDisplayGeometryValidator.conflicts(listOf(Box("negative", Vector3f(), Vector3f(-2f, 1f, 1f))))
        }
    }
    test("reproduces the factory panel and lamp sharing the front plane") {
        val panel = Box("panel", Vector3f(0f, 2.05f, -.65f), Vector3f(3.5f, 1.1f, .25f))
        val lamp = Box("lamp", Vector3f(1.2f, 2.22f, -.55f), Vector3f(.65f, .4f, .05f))
        val hits = WorksiteDisplayGeometryValidator.conflicts(listOf(panel, lamp))
        hits.size shouldBe 1
        hits.single().firstFace shouldBe "z+"
        hits.single().secondFace shouldBe "z+"
        val detached = lamp.copy(center = Vector3f(1.2f, 2.22f, -.495f))
        WorksiteDisplayGeometryValidator.conflicts(listOf(panel, detached)).isEmpty() shouldBe true
    }
    test("detects small depth discrepancies but accepts deliberately separated plates") {
        val a = Box("a", Vector3f(), Vector3f(2f, 2f, .1f))
        val b = Box("b", Vector3f(.3f, 0f, .0005f), Vector3f(1f, .8f, .1f))
        WorksiteDisplayGeometryValidator.conflicts(listOf(a, b)).size shouldBe 2
        WorksiteDisplayGeometryValidator.conflicts(listOf(a, b.copy(center = Vector3f(.3f, 0f, .01f)))).isEmpty() shouldBe true
    }
    test("actual rotated faces overlap even when their local axes differ") {
        val a = Box("a", Vector3f(), Vector3f(2f, .2f, .1f))
        val b = Box("b", Vector3f(), Vector3f(2f, .2f, .1f), Quaternionf().rotateZ((PI / 4).toFloat()))
        WorksiteDisplayGeometryValidator.conflicts(listOf(a, b)).map { it.firstFace }.toSet() shouldBe setOf("z-", "z+")
    }
    test("overlapping AABBs alone do not report separated parallel diagonal bars") {
        val rotation = Quaternionf().rotateZ((PI / 4).toFloat())
        val a = Box("a", Vector3f(), Vector3f(2f, .1f, .1f), rotation)
        val b = Box("b", Vector3f(-.25f, .25f, 0f), Vector3f(2f, .1f, .1f), rotation)
        WorksiteDisplayGeometryValidator.conflicts(listOf(a, b)).isEmpty() shouldBe true
    }
    test("face to face joins and edge contacts are valid") {
        val a = Box("a", Vector3f(), Vector3f(1f))
        val b = Box("b", Vector3f(1f, 0f, 0f), Vector3f(1f))
        WorksiteDisplayGeometryValidator.conflicts(listOf(a, b)).isEmpty() shouldBe true
    }
    test("whole assembly yaw does not hide a coplanar overlap") {
        val rotation = Quaternionf().rotateY(.73f)
        val a = Box("a", Vector3f(), Vector3f(2f, 1f, .1f), rotation)
        val b = Box("b", rotation.transform(Vector3f(.3f, 0f, 0f)), Vector3f(1f, .8f, .1f), rotation)
        WorksiteDisplayGeometryValidator.conflicts(listOf(a, b)).size shouldBe 2
    }
})
