/*
 * Copyright 2011-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.lettuce.core.pubsub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Fail.fail;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import io.lettuce.Delay;
import io.lettuce.TestClientResources;
import io.lettuce.Wait;
import io.lettuce.core.AbstractRedisClientTest;
import io.lettuce.core.FastShutdown;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.internal.LettuceFactories;
import io.lettuce.core.pubsub.api.reactive.ChannelMessage;
import io.lettuce.core.pubsub.api.reactive.PatternMessage;
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

/**
 * @author Mark Paluch
 */
public class PubSubReactiveTest extends AbstractRedisClientTest implements RedisPubSubListener<String, String> {

    private RedisPubSubReactiveCommands<String, String> pubsub;
    private RedisPubSubReactiveCommands<String, String> pubsub2;

    private BlockingQueue<String> channels;
    private BlockingQueue<String> patterns;
    private BlockingQueue<String> messages;
    private BlockingQueue<Long> counts;

    private String channel = "channel0";
    private String pattern = "channel*";
    private String message = "msg!";

    @Before
    public void openPubSubConnection() {

        pubsub = client.connectPubSub().reactive();
        pubsub2 = client.connectPubSub().reactive();
        pubsub.getStatefulConnection().addListener(this);
        channels = LettuceFactories.newBlockingQueue();
        patterns = LettuceFactories.newBlockingQueue();
        messages = LettuceFactories.newBlockingQueue();
        counts = LettuceFactories.newBlockingQueue();
    }

    @After
    public void closePubSubConnection() {
        pubsub.getStatefulConnection().close();
        pubsub2.getStatefulConnection().close();
    }

    @Test
    public void observeChannels() throws Exception {

        block(pubsub.subscribe(channel));

        BlockingQueue<ChannelMessage<String, String>> channelMessages = LettuceFactories.newBlockingQueue();

        Disposable disposable = pubsub.observeChannels().doOnNext(channelMessages::add).subscribe();

        redis.publish(channel, message);
        redis.publish(channel, message);
        redis.publish(channel, message);

        Wait.untilEquals(3, channelMessages::size).waitOrTimeout();
        assertThat(channelMessages).hasSize(3);

        disposable.dispose();
        redis.publish(channel, message);
        Delay.delay(Duration.ofMillis(500));
        assertThat(channelMessages).hasSize(3);

        ChannelMessage<String, String> channelMessage = channelMessages.take();
        assertThat(channelMessage.getChannel()).isEqualTo(channel);
        assertThat(channelMessage.getMessage()).isEqualTo(message);
    }

    @Test
    public void observeChannelsUnsubscribe() {

        block(pubsub.subscribe(channel));

        BlockingQueue<ChannelMessage<String, String>> channelMessages = LettuceFactories.newBlockingQueue();

        pubsub.observeChannels().doOnNext(channelMessages::add).subscribe().dispose();

        block(redis.getStatefulConnection().reactive().publish(channel, message));
        block(redis.getStatefulConnection().reactive().publish(channel, message));

        Delay.delay(Duration.ofMillis(500));
        assertThat(channelMessages).isEmpty();
    }

    @Test
    public void observePatterns() throws Exception {

        block(pubsub.psubscribe(pattern));

        BlockingQueue<PatternMessage<String, String>> patternMessages = LettuceFactories.newBlockingQueue();

        pubsub.observePatterns().doOnNext(patternMessages::add).subscribe();

        redis.publish(channel, message);
        redis.publish(channel, message);
        redis.publish(channel, message);

        Wait.untilTrue(() -> patternMessages.size() == 3).waitOrTimeout();
        assertThat(patternMessages).hasSize(3);

        PatternMessage<String, String> patternMessage = patternMessages.take();
        assertThat(patternMessage.getChannel()).isEqualTo(channel);
        assertThat(patternMessage.getMessage()).isEqualTo(message);
        assertThat(patternMessage.getPattern()).isEqualTo(pattern);
    }

    @Test
    public void observePatternsWithUnsubscribe() {

        block(pubsub.psubscribe(pattern));

        BlockingQueue<PatternMessage<String, String>> patternMessages = LettuceFactories.newBlockingQueue();

        Disposable subscription = pubsub.observePatterns().doOnNext(patternMessages::add).subscribe();

        redis.publish(channel, message);
        redis.publish(channel, message);
        redis.publish(channel, message);

        Wait.untilTrue(() -> patternMessages.size() == 3).waitOrTimeout();
        assertThat(patternMessages).hasSize(3);
        subscription.dispose();

        redis.publish(channel, message);
        redis.publish(channel, message);
        redis.publish(channel, message);

        Delay.delay(Duration.ofMillis(500));

        assertThat(patternMessages).hasSize(3);
    }

    @Test(timeout = 2000)
    public void message() throws Exception {

        block(pubsub.subscribe(channel));
        assertThat(channels.take()).isEqualTo(channel);

        redis.publish(channel, message);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void pmessage() throws Exception {

        block(pubsub.psubscribe(pattern));
        assertThat(patterns.take()).isEqualTo(pattern);

        redis.publish(channel, message);
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);

        redis.publish("channel2", "msg 2!");
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat(channels.take()).isEqualTo("channel2");
        assertThat(messages.take()).isEqualTo("msg 2!");
    }

    @Test(timeout = 2000)
    public void psubscribe() throws Exception {

        block(pubsub.psubscribe(pattern));

        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void pubsubEmptyChannels() {

        pubsub.subscribe();
        fail("Missing IllegalArgumentException: channels must not be empty");
    }

    @Test
    public void pubsubChannels() {

        block(pubsub.subscribe(channel));
        List<String> result = block(pubsub2.pubsubChannels().collectList());
        assertThat(result).contains(channel);
    }

    @Test
    public void pubsubMultipleChannels() {

        StepVerifier.create(pubsub.subscribe(channel, "channel1", "channel3")).verifyComplete();

        StepVerifier.create(pubsub2.pubsubChannels().collectList())
                .consumeNextWith(actual -> assertThat(actual).contains(channel, "channel1", "channel3")).verifyComplete();
    }

    @Test
    public void pubsubChannelsWithArg() {

        StepVerifier.create(pubsub.subscribe(channel)).verifyComplete();
        Wait.untilTrue(() -> mono(pubsub2.pubsubChannels(pattern).filter(s -> channel.equals(s))) != null).waitOrTimeout();

        String result = mono(pubsub2.pubsubChannels(pattern).filter(s -> channel.equals(s)));
        assertThat(result).isEqualToIgnoringCase(channel);
    }

    @Test
    public void pubsubNumsub() {

        StepVerifier.create(pubsub.subscribe(channel)).verifyComplete();

        Wait.untilEquals(1, () -> block(pubsub2.pubsubNumsub(channel)).size()).waitOrTimeout();

        Map<String, Long> result = block(pubsub2.pubsubNumsub(channel));
        assertThat(result).hasSize(1);
        assertThat(result).containsKeys(channel);
    }

    @Test
    public void pubsubNumpat() {

        Wait.untilEquals(0L, () -> block(pubsub2.pubsubNumpat())).waitOrTimeout();

        StepVerifier.create(pubsub.psubscribe(pattern)).verifyComplete();
        Wait.untilEquals(1L, () -> redis.pubsubNumpat()).waitOrTimeout();

        Long result = block(pubsub2.pubsubNumpat());
        assertThat(result.longValue()).isGreaterThan(0);
    }

    @Test(timeout = 2000)
    public void punsubscribe() throws Exception {

        StepVerifier.create(pubsub.punsubscribe(pattern)).verifyComplete();
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(0);

    }

    @Test(timeout = 2000)
    public void subscribe() throws Exception {

        StepVerifier.create(pubsub.subscribe(channel)).verifyComplete();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isGreaterThan(0);
    }

    @Test(timeout = 2000)
    public void unsubscribe() throws Exception {

        StepVerifier.create(pubsub.unsubscribe(channel)).verifyComplete();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(0);

        block(pubsub.unsubscribe());

        assertThat(channels).isEmpty();
        assertThat(patterns).isEmpty();

    }

    @Test
    public void pubsubCloseOnClientShutdown() {

        RedisClient redisClient = RedisClient.create(TestClientResources.get(), RedisURI.Builder.redis(host, port).build());

        RedisPubSubCommands<String, String> connection = redisClient.connectPubSub().sync();
        FastShutdown.shutdown(redisClient);

        assertThat(connection.isOpen()).isFalse();
    }

    @Test(timeout = 2000)
    public void utf8Channel() throws Exception {

        String channel = "channelλ";
        String message = "αβγ";

        block(pubsub.subscribe(channel));
        assertThat(channels.take()).isEqualTo(channel);

        StepVerifier.create(pubsub2.publish(channel, message)).expectNextCount(1).verifyComplete();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void resubscribeChannelsOnReconnect() throws Exception {

        StepVerifier.create(pubsub.subscribe(channel)).verifyComplete();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(1);

        block(pubsub.quit());
        assertThat(channels.take()).isEqualTo(channel);
        assertThat((long) counts.take()).isEqualTo(1);

        Wait.untilTrue(pubsub::isOpen).waitOrTimeout();

        redis.publish(channel, message);
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void resubscribePatternsOnReconnect() throws Exception {

        StepVerifier.create(pubsub.psubscribe(pattern)).verifyComplete();
        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);

        block(pubsub.quit());

        assertThat(patterns.take()).isEqualTo(pattern);
        assertThat((long) counts.take()).isEqualTo(1);

        Wait.untilTrue(pubsub::isOpen).waitOrTimeout();

        StepVerifier.create(pubsub2.publish(channel, message)).expectNextCount(1).verifyComplete();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);
    }

    @Test(timeout = 2000)
    public void adapter() throws Exception {

        final BlockingQueue<Long> localCounts = LettuceFactories.newBlockingQueue();

        RedisPubSubAdapter<String, String> adapter = new RedisPubSubAdapter<String, String>() {
            @Override
            public void subscribed(String channel, long count) {
                super.subscribed(channel, count);
                localCounts.add(count);
            }

            @Override
            public void unsubscribed(String channel, long count) {
                super.unsubscribed(channel, count);
                localCounts.add(count);
            }
        };

        pubsub.getStatefulConnection().addListener(adapter);
        StepVerifier.create(pubsub.subscribe(channel)).verifyComplete();
        StepVerifier.create(pubsub.psubscribe(pattern)).verifyComplete();

        assertThat((long) localCounts.take()).isEqualTo(1L);

        StepVerifier.create(pubsub2.publish(channel, message)).expectNextCount(1).verifyComplete();
        StepVerifier.create(pubsub.punsubscribe(pattern)).verifyComplete();
        StepVerifier.create(pubsub.unsubscribe(channel)).verifyComplete();

        assertThat((long) localCounts.take()).isEqualTo(0L);
    }

    @Test(timeout = 2000)
    public void removeListener() throws Exception {

        StepVerifier.create(pubsub.subscribe(channel)).verifyComplete();
        assertThat(channels.take()).isEqualTo(channel);

        StepVerifier.create(pubsub2.publish(channel, message)).expectNextCount(1).verifyComplete();
        assertThat(channels.take()).isEqualTo(channel);
        assertThat(messages.take()).isEqualTo(message);

        pubsub.getStatefulConnection().removeListener(this);

        StepVerifier.create(pubsub2.publish(channel, message)).expectNextCount(1).verifyComplete();
        assertThat(channels.poll(10, TimeUnit.MILLISECONDS)).isNull();
        assertThat(messages.poll(10, TimeUnit.MILLISECONDS)).isNull();
    }

    // RedisPubSubListener implementation
    @Override
    public void message(String channel, String message) {

        channels.add(channel);
        messages.add(message);
    }

    @Override
    public void message(String pattern, String channel, String message) {
        patterns.add(pattern);
        channels.add(channel);
        messages.add(message);
    }

    @Override
    public void subscribed(String channel, long count) {
        channels.add(channel);
        counts.add(count);
    }

    @Override
    public void psubscribed(String pattern, long count) {
        patterns.add(pattern);
        counts.add(count);
    }

    @Override
    public void unsubscribed(String channel, long count) {
        channels.add(channel);
        counts.add(count);
    }

    @Override
    public void punsubscribed(String pattern, long count) {
        patterns.add(pattern);
        counts.add(count);
    }

    protected <T> T block(Mono<T> mono) {
        return mono.block();
    }

    protected <T> T mono(Flux<T> flux) {
        return flux.next().block();
    }

    protected <T> List<T> all(Flux<T> flux) {
        return flux.collectList().block();
    }
}
