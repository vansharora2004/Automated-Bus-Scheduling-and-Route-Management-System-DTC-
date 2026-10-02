package com.dtc.transit.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Loads a dataset from the command line.
 *
 * <p>Behind the {@code seed} profile, so it cannot run by accident. It truncates the operational tables, and
 * an unguarded bean that did that on startup would be a loaded gun pointed at whatever environment the
 * application happened to be started in.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=seed \
 *     -Dspring-boot.run.arguments="--size=M --purge"
 * </pre>
 */
@Component
@Profile("seed")
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private final DatasetGenerator generator;

    public SeedRunner(DatasetGenerator generator) {
        this.generator = generator;
    }

    @Override
    public void run(ApplicationArguments args) {
        DatasetSize size = sizeFrom(args);
        boolean purge = args.containsOption("purge");

        if (!purge) {
            // Loading on top of existing data would violate the unique codes the generator assumes it owns,
            // and the failure would arrive thousands of rows in. Refusing up front says so plainly.
            log.warn("--purge was not given: generation will fail if the database already holds a dataset");
        }

        var report = generator.generate(size, purge);

        log.info(
                """
                dataset {} loaded in {} ms
                  depots      {}
                  routes      {}
                  stops       {}
                  timetables  {}
                  trips       {}
                  buses       {}
                  crew        {}
                  deadheads   {}
                  checksum    {}""",
                report.size(),
                report.millis(),
                report.depots(),
                report.routes(),
                report.stops(),
                report.timetables(),
                report.trips(),
                report.buses(),
                report.crew(),
                report.deadheads(),
                report.checksum());
    }

    private static DatasetSize sizeFrom(ApplicationArguments args) {
        var values = args.getOptionValues("size");
        if (values == null || values.isEmpty()) {
            return DatasetSize.S;
        }
        return DatasetSize.valueOf(values.get(0).trim().toUpperCase(java.util.Locale.ROOT));
    }
}
