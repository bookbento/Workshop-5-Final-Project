package com.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// ข้อมูลหมวดหมู่สินค้า
@Serializable
data class Category(val id: Int, val name: String)

// ข้อมูลสินค้า มีความสัมพันธ์กับ Category ผ่าน categoryId
@Serializable
data class Product(
        val id: Int,
        val name: String,
        val description: String,
        val price: Double,
        val stockQuantity: Int,  // จำนวนสินค้าคงคลัง
        val categoryId: Int      // หมวดหมู่ที่สินค้านี้สังกัด
)

// รูปแบบข้อมูลสำหรับรับคำขอเพิ่มหรือลดสต็อกสินค้า
@Serializable
data class StockUpdateRequest(val quantityToAdd: Int)

// จำลองฐานข้อมูลแบบ in-memory โดยใช้ MutableList เก็บข้อมูลหมวดหมู่และสินค้า
object DataStorage {
    val categories = mutableListOf<Category>()
    val products = mutableListOf<Product>()
}

fun Application.configureRouting() {
    routing {
        // ------------------- Categories CRUD -------------------
        route("/categories") {
            // ดึงข้อมูลหมวดหมู่ทั้งหมด
            get {
                call.respond(DataStorage.categories)
            }

            // สร้างหมวดหมู่ใหม่
            post {
                val category = call.receive<Category>()

                // เช็คว่ามี id ซ้ำหรือยัง
                if (DataStorage.categories.any { it.id == category.id }) {
                    call.respond(HttpStatusCode.Conflict, "Category with this ID already exists")
                    return@post
                }

                // เพิ่มหมวดหมู่ใหม่
                DataStorage.categories.add(category)
                call.respond(HttpStatusCode.Created, category)
            }

            // แก้ไขหมวดหมู่ตาม id
            put("/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid ID")

                val categoryIndex = DataStorage.categories.indexOfFirst { it.id == id }
                if (categoryIndex == -1) return@put call.respond(HttpStatusCode.NotFound, "Category not found")

                val newData = call.receive<Category>()

                // อัพเดตชื่อหมวดหมู่ โดยป้องกันไม่ให้แก้ไข id
                DataStorage.categories[categoryIndex] = newData.copy(id = id)

                call.respond(DataStorage.categories[categoryIndex])
            }

            // ลบหมวดหมู่ตาม id
            delete("/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ID")

                val removed = DataStorage.categories.removeIf { it.id == id }
                if (removed) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound, "Category not found")
            }
        }

        // ------------------- Products CRUD -------------------
        route("/products") {
            // ดึงรายการสินค้าทั้งหมด
            get {
                call.respond(DataStorage.products)
            }

            // สร้างสินค้าใหม่
            post {
                val product = call.receive<Product>()

                // เช็ค id สินค้าซ้ำ
                if (DataStorage.products.any { it.id == product.id }) {
                    call.respond(HttpStatusCode.Conflict, "Product with this ID already exists")
                    return@post
                }

                // เช็คว่า categoryId มีอยู่จริงไหม
                if (DataStorage.categories.none { it.id == product.categoryId }) {
                    call.respond(HttpStatusCode.BadRequest, "CategoryId does not exist")
                    return@post
                }

                // เช็ค stockQuantity ต้องไม่ติดลบ
                if (product.stockQuantity < 0) {
                    call.respond(HttpStatusCode.BadRequest, "Stock quantity cannot be negative")
                    return@post
                }

                DataStorage.products.add(product)
                call.respond(HttpStatusCode.Created, product)
            }

            // แก้ไขสินค้าตาม id
            put("/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid ID")

                val productIndex = DataStorage.products.indexOfFirst { it.id == id }
                if (productIndex == -1) return@put call.respond(HttpStatusCode.NotFound, "Product not found")

                val newData = call.receive<Product>()

                // ตรวจสอบ categoryId และ stockQuantity ใหม่
                if (DataStorage.categories.none { it.id == newData.categoryId }) {
                    call.respond(HttpStatusCode.BadRequest, "CategoryId does not exist")
                    return@put
                }
                if (newData.stockQuantity < 0) {
                    call.respond(HttpStatusCode.BadRequest, "Stock quantity cannot be negative")
                    return@put
                }

                // อัพเดตข้อมูลสินค้า โดยไม่แก้ id
                DataStorage.products[productIndex] = newData.copy(id = id)
                call.respond(DataStorage.products[productIndex])
            }

            // ลบสินค้าตาม id
            delete("/{id}") {
                val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ID")

                val removed = DataStorage.products.removeIf { it.id == id }
                if (removed) call.respond(HttpStatusCode.NoContent)
                else call.respond(HttpStatusCode.NotFound, "Product not found")
            }

            // ------------------- Endpoint เพิ่มสต็อกสินค้า -------------------
            post("/{id}/add-stock") {
                val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid ID")

                val productIndex = DataStorage.products.indexOfFirst { it.id == id }
                if (productIndex == -1) return@post call.respond(HttpStatusCode.NotFound, "Product not found")

                val stockUpdate = call.receive<StockUpdateRequest>()

                // ตรวจสอบว่าปริมาณที่เพิ่มต้องไม่ติดลบ
                if (stockUpdate.quantityToAdd < 0) {
                    call.respond(HttpStatusCode.BadRequest, "Quantity to add must be non-negative")
                    return@post
                }

                val oldProduct = DataStorage.products[productIndex]
                val newStock = oldProduct.stockQuantity + stockUpdate.quantityToAdd

                // อัพเดตจำนวนสต็อก
                val updatedProduct = oldProduct.copy(stockQuantity = newStock)
                DataStorage.products[productIndex] = updatedProduct

                call.respond(updatedProduct)
            }

            // ------------------- Endpoint ลดสต็อกสินค้า (Optional) -------------------
            post("/{id}/reduce-stock") {
                val id = call.parameters["id"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid ID")

                val productIndex = DataStorage.products.indexOfFirst { it.id == id }
                if (productIndex == -1) return@post call.respond(HttpStatusCode.NotFound, "Product not found")

                val stockUpdate = call.receive<StockUpdateRequest>()

                // ปริมาณที่ลดต้องไม่ติดลบ
                if (stockUpdate.quantityToAdd < 0) {
                    call.respond(HttpStatusCode.BadRequest, "Quantity to reduce must be non-negative")
                    return@post
                }

                val oldProduct = DataStorage.products[productIndex]
                val newStock = oldProduct.stockQuantity - stockUpdate.quantityToAdd

                // หลีกเลี่ยงสต็อกติดลบ
                if (newStock < 0) {
                    call.respond(HttpStatusCode.BadRequest, "Stock quantity cannot be negative")
                    return@post
                }

                val updatedProduct = oldProduct.copy(stockQuantity = newStock)
                DataStorage.products[productIndex] = updatedProduct

                call.respond(updatedProduct)
            }
        }
    }
}
