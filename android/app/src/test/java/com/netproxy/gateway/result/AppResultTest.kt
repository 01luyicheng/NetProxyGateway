package com.netproxy.gateway.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResultTest {

    @Test
    fun `getOrNull returns data on Success`() {
        val result = AppResult.Success("test_data")
        assertEquals("test_data", result.getOrNull())
    }

    @Test
    fun `getOrNull returns null on Error`() {
        val result = AppResult.Error(Exception("test error"))
        assertNull(result.getOrNull())
    }

    @Test
    fun `exceptionOrNull returns exception on Error`() {
        val exception = Exception("test error")
        val result = AppResult.Error(exception)
        assertEquals(exception, result.exceptionOrNull())
    }

    @Test
    fun `exceptionOrNull returns null on Success`() {
        val result = AppResult.Success("test_data")
        assertNull(result.exceptionOrNull())
    }

    @Test
    fun `isSuccess returns true for Success and false for Error`() {
        val success = AppResult.Success("test")
        val error = AppResult.Error(Exception("error"))
        assertTrue(success.isSuccess())
        assertFalse(error.isSuccess())
    }

    @Test
    fun `isError returns true for Error and false for Success`() {
        val success = AppResult.Success("test")
        val error = AppResult.Error(Exception("error"))
        assertFalse(success.isError())
        assertTrue(error.isError())
    }

    @Test
    fun `companion success creates Success instance`() {
        val result = AppResult.success("data")
        assertTrue(result is AppResult.Success)
        assertEquals("data", (result as AppResult.Success).data)
    }

    @Test
    fun `companion error creates Error instance`() {
        val exception = RuntimeException("error")
        val result = AppResult.error<String>(exception)
        assertTrue(result is AppResult.Error)
        assertEquals(exception, (result as AppResult.Error).exception)
    }

    @Test
    fun `fromResult creates Success from Result-success`() {
        val result = Result.success("data")
        val appResult = AppResult.fromResult(result)
        assertTrue(appResult is AppResult.Success)
        assertEquals("data", (appResult as AppResult.Success).data)
    }

    @Test
    fun `fromResult creates Error from Result-failure`() {
        val exception = Exception("error")
        val result = Result.failure<String>(exception)
        val appResult = AppResult.fromResult(result)
        assertTrue(appResult is AppResult.Error)
        assertEquals(exception, (appResult as AppResult.Error).exception)
    }

    @Test
    fun `runCatching returns Success when no exception is thrown`() {
        val result = AppResult.runCatching { "success_data" }
        assertTrue(result is AppResult.Success)
        assertEquals("success_data", (result as AppResult.Success).data)
    }

    @Test
    fun `runCatching returns Error when exception is thrown`() {
        val exception = RuntimeException("test error")
        val result = AppResult.runCatching { throw exception }
        assertTrue(result is AppResult.Error)
        assertEquals(exception, (result as AppResult.Error).exception)
    }

    @Test
    fun `onSuccess calls action on Success`() {
        var called = false
        val result = AppResult.Success("data")
        val returnedResult = result.onSuccess {
            called = true
            assertEquals("data", it)
        }
        assertTrue(called)
        assertEquals(result, returnedResult)
    }

    @Test
    fun `onSuccess does not call action on Error`() {
        var called = false
        val result = AppResult.Error(Exception("error"))
        val returnedResult = result.onSuccess { called = true }
        assertFalse(called)
        assertEquals(result, returnedResult)
    }

    @Test
    fun `onError calls action on Error`() {
        var called = false
        val exception = Exception("error")
        val result = AppResult.Error(exception)
        val returnedResult = result.onError {
            called = true
            assertEquals(exception, it)
        }
        assertTrue(called)
        assertEquals(result, returnedResult)
    }

    @Test
    fun `onError does not call action on Success`() {
        var called = false
        val result = AppResult.Success("data")
        val returnedResult = result.onError { called = true }
        assertFalse(called)
        assertEquals(result, returnedResult)
    }

    @Test
    fun `map transforms Success value`() {
        val result = AppResult.Success(10)
        val mapped = result.map { it.toString() }
        assertTrue(mapped is AppResult.Success)
        assertEquals("10", (mapped as AppResult.Success).data)
    }

    @Test
    fun `map does not transform Error value`() {
        val exception = Exception("error")
        val result = AppResult.error<Int>(exception)
        val mapped = result.map { it.toString() }
        assertTrue(mapped is AppResult.Error)
        assertEquals(exception, (mapped as AppResult.Error).exception)
    }

    @Test
    fun `mapError transforms Error exception`() {
        val originalException = Exception("original")
        val mappedException = RuntimeException("mapped")
        val result = AppResult.Error(originalException)
        val mapped = result.mapError { mappedException }
        assertTrue(mapped is AppResult.Error)
        assertEquals(mappedException, (mapped as AppResult.Error).exception)
    }

    @Test
    fun `mapError does not transform Success`() {
        val result = AppResult.Success("data")
        val mapped = result.mapError { RuntimeException("mapped") }
        assertTrue(mapped is AppResult.Success)
        assertEquals("data", (mapped as AppResult.Success).data)
    }

    @Test
    fun `flatMap chains Success`() {
        val result = AppResult.Success(10)
        val flatMapped = result.flatMap { AppResult.Success(it.toString()) }
        assertTrue(flatMapped is AppResult.Success)
        assertEquals("10", (flatMapped as AppResult.Success).data)
    }

    @Test
    fun `flatMap chains Success to Error`() {
        val exception = Exception("chained error")
        val result = AppResult.Success(10)
        val flatMapped = result.flatMap { AppResult.error<String>(exception) }
        assertTrue(flatMapped is AppResult.Error)
        assertEquals(exception, (flatMapped as AppResult.Error).exception)
    }

    @Test
    fun `flatMap returns Error when applied on Error`() {
        val exception = Exception("original error")
        val result = AppResult.error<Int>(exception)
        val flatMapped = result.flatMap { AppResult.Success(it.toString()) }
        assertTrue(flatMapped is AppResult.Error)
        assertEquals(exception, (flatMapped as AppResult.Error).exception)
    }

    @Test
    fun `toResult converts Success to Result-success`() {
        val result = AppResult.Success("data")
        val standardResult = result.toResult()
        assertTrue(standardResult.isSuccess)
        assertEquals("data", standardResult.getOrNull())
    }

    @Test
    fun `toResult converts Error to Result-failure`() {
        val exception = Exception("error")
        val result = AppResult.Error(exception)
        val standardResult = result.toResult()
        assertTrue(standardResult.isFailure)
        assertEquals(exception, standardResult.exceptionOrNull())
    }

    @Test
    fun `getOrDefault returns value on Success`() {
        val result = AppResult.Success("data")
        assertEquals("data", result.getOrDefault("default"))
    }

    @Test
    fun `getOrDefault returns default on Error`() {
        val result = AppResult.error<String>(Exception("error"))
        assertEquals("default", result.getOrDefault("default"))
    }

    @Test
    fun `getOrElse returns value on Success`() {
        val result = AppResult.Success("data")
        val value = result.getOrElse { "default from exception" }
        assertEquals("data", value)
    }

    @Test
    fun `getOrElse evaluates block on Error`() {
        val result = AppResult.error<String>(Exception("error"))
        val value = result.getOrElse { it.message ?: "default" }
        assertEquals("error", value)
    }

    @Test
    fun `getOrThrow returns value on Success`() {
        val result = AppResult.Success("data")
        assertEquals("data", result.getOrThrow())
    }

    @Test
    fun `getOrThrow throws exception on Error`() {
        val exception = Exception("error")
        val result = AppResult.error<String>(exception)
        val thrown = assertThrows(Exception::class.java) { result.getOrThrow() }
        assertSame(exception, thrown)
    }

    @Test
    fun `recover transforms Error to Success`() {
        val exception = Exception("error")
        val result = AppResult.Error(exception)
        val recovered = result.recover { "recovered" }
        assertTrue(recovered is AppResult.Success)
        assertEquals("recovered", (recovered as AppResult.Success).data)
    }

    @Test
    fun `recover does not transform Success`() {
        val result = AppResult.Success("data")
        val recovered = result.recover { "recovered" }
        assertTrue(recovered is AppResult.Success)
        assertEquals("data", (recovered as AppResult.Success).data)
    }

    @Test
    fun `recoverCatching transforms Error to Success`() {
        val exception = Exception("error")
        val result = AppResult.Error(exception)
        val recovered = result.recoverCatching { "recovered" }
        assertTrue(recovered is AppResult.Success)
        assertEquals("recovered", (recovered as AppResult.Success).data)
    }

    @Test
    fun `recoverCatching catches exception during recovery and returns new Error`() {
        val exception = Exception("original error")
        val newException = RuntimeException("recovery error")
        val result = AppResult.Error(exception)
        val recovered = result.recoverCatching { throw newException }
        assertTrue(recovered is AppResult.Error)
        assertEquals(newException, (recovered as AppResult.Error).exception)
    }

    @Test
    fun `recoverCatching does not transform Success`() {
        val result = AppResult.Success("data")
        val recovered = result.recoverCatching { "recovered" }
        assertTrue(recovered is AppResult.Success)
        assertEquals("data", (recovered as AppResult.Success).data)
    }

    @Test
    fun `filter returns original Success if predicate matches`() {
        val result = AppResult.Success(10)
        val filtered = result.filter({ it > 5 }) { Exception("too small") }
        assertTrue(filtered is AppResult.Success)
        assertEquals(10, (filtered as AppResult.Success).data)
    }

    @Test
    fun `filter returns Error if predicate fails`() {
        val result = AppResult.Success(2)
        val exception = Exception("too small")
        val filtered = result.filter({ it > 5 }) { exception }
        assertTrue(filtered is AppResult.Error)
        assertEquals(exception, (filtered as AppResult.Error).exception)
    }

    @Test
    fun `filter ignores Error`() {
        val originalException = Exception("original")
        val result = AppResult.error<Int>(originalException)
        val filtered = result.filter({ it > 5 }) { Exception("too small") }
        assertTrue(filtered is AppResult.Error)
        assertEquals(originalException, (filtered as AppResult.Error).exception)
    }

    @Test
    fun `zip combines two Success results`() {
        val r1 = AppResult.Success("Hello")
        val r2 = AppResult.Success("World")
        val zipped = r1.zip(r2) { a, b -> "$a $b" }
        assertTrue(zipped is AppResult.Success)
        assertEquals("Hello World", (zipped as AppResult.Success).data)
    }

    @Test
    fun `zip returns first Error when first result is Error`() {
        val exception = Exception("error 1")
        val r1 = AppResult.error<String>(exception)
        val r2 = AppResult.Success("World")
        val zipped = r1.zip(r2) { a, b -> "$a $b" }
        assertTrue(zipped is AppResult.Error)
        assertEquals(exception, (zipped as AppResult.Error).exception)
    }

    @Test
    fun `zip returns second Error when second result is Error`() {
        val exception = Exception("error 2")
        val r1 = AppResult.Success("Hello")
        val r2 = AppResult.error<String>(exception)
        val zipped = r1.zip(r2) { a, b -> "$a $b" }
        assertTrue(zipped is AppResult.Error)
        assertEquals(exception, (zipped as AppResult.Error).exception)
    }

    @Test
    fun `zip returns first Error when both results are Error`() {
        val exception1 = Exception("error 1")
        val exception2 = Exception("error 2")
        val r1 = AppResult.error<String>(exception1)
        val r2 = AppResult.error<String>(exception2)
        val zipped = r1.zip(r2) { a, b -> "$a $b" }
        assertTrue(zipped is AppResult.Error)
        assertEquals(exception1, (zipped as AppResult.Error).exception)
    }
}
