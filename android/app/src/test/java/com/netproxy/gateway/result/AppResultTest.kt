package com.netproxy.gateway.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class AppResultTest {

    @Test
    fun testSuccessState() {
        val data = "test_data"
        val result = AppResult.success(data)

        assertTrue(result.isSuccess())
        assertFalse(result.isError())
        assertEquals(data, result.getOrNull())
        assertNull(result.exceptionOrNull())
    }

    @Test
    fun testErrorState() {
        val exception = IOException("test_error")
        val result = AppResult.error<String>(exception)

        assertTrue(result.isError())
        assertFalse(result.isSuccess())
        assertEquals(exception, result.exceptionOrNull())
        assertNull(result.getOrNull())
    }

    @Test
    fun testFromResult() {
        val successResult = Result.success("data")
        val appResultSuccess = AppResult.fromResult(successResult)
        assertTrue(appResultSuccess.isSuccess())
        assertEquals("data", appResultSuccess.getOrNull())

        val exception = RuntimeException("error")
        val failureResult = Result.failure<String>(exception)
        val appResultFailure = AppResult.fromResult(failureResult)
        assertTrue(appResultFailure.isError())
        assertEquals(exception, appResultFailure.exceptionOrNull())
    }

    @Test
    fun testRunCatching() {
        val successResult = AppResult.runCatching { "data" }
        assertTrue(successResult.isSuccess())
        assertEquals("data", successResult.getOrNull())

        val exception = IllegalArgumentException("error")
        val errorResult = AppResult.runCatching { throw exception }
        assertTrue(errorResult.isError())
        assertEquals(exception, errorResult.exceptionOrNull())
    }

    @Test
    fun testOnSuccess() {
        var called = false
        val result = AppResult.success("data")
        result.onSuccess {
            called = true
            assertEquals("data", it)
        }
        assertTrue(called)

        called = false
        val errorResult = AppResult.error<String>(Exception())
        errorResult.onSuccess {
            called = true
        }
        assertFalse(called)
    }

    @Test
    fun testOnError() {
        var called = false
        val exception = Exception("error")
        val result = AppResult.error<String>(exception)
        result.onError {
            called = true
            assertEquals(exception, it)
        }
        assertTrue(called)

        called = false
        val successResult = AppResult.success("data")
        successResult.onError {
            called = true
        }
        assertFalse(called)
    }

    @Test
    fun testMap() {
        val successResult = AppResult.success(10)
        val mappedSuccess = successResult.map { it * 2 }
        assertTrue(mappedSuccess.isSuccess())
        assertEquals(20, mappedSuccess.getOrNull())

        val errorResult = AppResult.error<Int>(Exception("error"))
        val mappedError = errorResult.map { it * 2 }
        assertTrue(mappedError.isError())
    }

    @Test
    fun testMapError() {
        val exception = IOException("error")
        val errorResult = AppResult.error<Int>(exception)
        val mappedError = errorResult.mapError { RuntimeException(it.message) }
        assertTrue(mappedError.isError())
        assertTrue(mappedError.exceptionOrNull() is RuntimeException)
        assertEquals("error", mappedError.exceptionOrNull()?.message)

        val successResult = AppResult.success(10)
        val mappedSuccess = successResult.mapError { RuntimeException("mapped") }
        assertTrue(mappedSuccess.isSuccess())
        assertEquals(10, mappedSuccess.getOrNull())
    }

    @Test
    fun testFlatMap() {
        val successResult = AppResult.success(10)
        val flatMappedSuccess = successResult.flatMap { AppResult.success(it.toString()) }
        assertTrue(flatMappedSuccess.isSuccess())
        assertEquals("10", flatMappedSuccess.getOrNull())

        val flatMappedToError = successResult.flatMap { AppResult.error<String>(Exception("error")) }
        assertTrue(flatMappedToError.isError())

        val errorResult = AppResult.error<Int>(Exception("error"))
        val flatMappedError = errorResult.flatMap { AppResult.success(it.toString()) }
        assertTrue(flatMappedError.isError())
    }

    @Test
    fun testGetOrDefault() {
        val successResult = AppResult.success(10)
        assertEquals(10, successResult.getOrDefault(20))

        val errorResult = AppResult.error<Int>(Exception("error"))
        assertEquals(20, errorResult.getOrDefault(20))
    }

    @Test
    fun testGetOrElse() {
        val successResult = AppResult.success(10)
        assertEquals(10, successResult.getOrElse { 20 })

        val errorResult = AppResult.error<Int>(Exception("error"))
        assertEquals(20, errorResult.getOrElse { 20 })
    }

    @Test
    fun testGetOrThrow() {
        val successResult = AppResult.success(10)
        assertEquals(10, successResult.getOrThrow())

        val exception = IllegalArgumentException("test error")
        val errorResult = AppResult.error<Int>(exception)
        try {
            errorResult.getOrThrow()
            fail("Expected exception was not thrown")
        } catch (e: Exception) {
            assertEquals(exception, e)
        }
    }

    @Test
    fun testToResult() {
        val successResult = AppResult.success(10)
        val stdSuccess = successResult.toResult()
        assertTrue(stdSuccess.isSuccess)
        assertEquals(10, stdSuccess.getOrNull())

        val exception = Exception("error")
        val errorResult = AppResult.error<Int>(exception)
        val stdFailure = errorResult.toResult()
        assertTrue(stdFailure.isFailure)
        assertEquals(exception, stdFailure.exceptionOrNull())
    }

    @Test
    fun testRecover() {
        val exception = Exception("error")
        val errorResult = AppResult.error<Int>(exception)
        val recovered = errorResult.recover { 20 }
        assertTrue(recovered.isSuccess())
        assertEquals(20, recovered.getOrNull())

        val successResult = AppResult.success(10)
        val notRecovered = successResult.recover { 20 }
        assertTrue(notRecovered.isSuccess())
        assertEquals(10, notRecovered.getOrNull())
    }

    @Test
    fun testRecoverCatching() {
        val errorResult = AppResult.error<Int>(Exception("error"))

        val recoveredSuccess = errorResult.recoverCatching { 20 }
        assertTrue(recoveredSuccess.isSuccess())
        assertEquals(20, recoveredSuccess.getOrNull())

        val recoveredError = errorResult.recoverCatching { throw IllegalArgumentException("new error") }
        assertTrue(recoveredError.isError())
        assertTrue(recoveredError.exceptionOrNull() is IllegalArgumentException)

        val successResult = AppResult.success(10)
        val notRecovered = successResult.recoverCatching { 20 }
        assertTrue(notRecovered.isSuccess())
        assertEquals(10, notRecovered.getOrNull())
    }

    @Test
    fun testFilter() {
        val successResult = AppResult.success(10)

        val filteredSuccess = successResult.filter({ it > 5 }) { Exception("too small") }
        assertTrue(filteredSuccess.isSuccess())
        assertEquals(10, filteredSuccess.getOrNull())

        val filteredError = successResult.filter({ it > 15 }) { Exception("too small") }
        assertTrue(filteredError.isError())
        assertEquals("too small", filteredError.exceptionOrNull()?.message)

        val errorResult = AppResult.error<Int>(Exception("original error"))
        val notFilteredError = errorResult.filter({ it > 5 }) { Exception("too small") }
        assertTrue(notFilteredError.isError())
        assertEquals("original error", notFilteredError.exceptionOrNull()?.message)
    }

    @Test
    fun testZip() {
        val success1 = AppResult.success(10)
        val success2 = AppResult.success(20)
        val error1 = AppResult.error<Int>(Exception("error1"))
        val error2 = AppResult.error<Int>(Exception("error2"))

        val zippedSuccess = success1.zip(success2) { a, b -> a + b }
        assertTrue(zippedSuccess.isSuccess())
        assertEquals(30, zippedSuccess.getOrNull())

        val zippedError1 = error1.zip(success2) { a, b -> a + b }
        assertTrue(zippedError1.isError())
        assertEquals("error1", zippedError1.exceptionOrNull()?.message)

        val zippedError2 = success1.zip(error2) { a, b -> a + b }
        assertTrue(zippedError2.isError())
        assertEquals("error2", zippedError2.exceptionOrNull()?.message)

        val zippedBothError = error1.zip(error2) { a, b -> a + b }
        assertTrue(zippedBothError.isError())
        assertEquals("error1", zippedBothError.exceptionOrNull()?.message)
    }
}
