package com.apollographql.apollo.api

/**
 * Represents a GraphQL subscription.
 */
interface Subscription<D : Operation.Data> : Operation<D>