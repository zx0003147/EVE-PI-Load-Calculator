import com.vepi.balance.ProductionBlock;
import com.vepi.balance.ProductionBlockCalculator;
import com.vepi.capacity.ProductionCapacityExtractor;
import com.vepi.sde.SdeRepository;
import com.vepi.template.TemplateParser;
import java.nio.file.Files;
import java.nio.file.Path;

public class ProbeBlock {
    public static void main(String[] args) throws Exception {
        SdeRepository sde = new SdeRepository("data/sde/pi-sde.db");
        TemplateParser parser = new TemplateParser();
        ProductionCapacityExtractor extractor = new ProductionCapacityExtractor(sde);
        for (String path : args) {
            ProductionBlock b = ProductionBlockCalculator.calculate(
                    extractor.extract(parser.parse(Files.readString(Path.of(path)))));
            System.out.println(path + " base=" + b.basePeriodSeconds() + "s");
            System.out.println("  external: " + b.externalRequirements());
            System.out.println("  outputs:  " + b.netOutputs());
        }
        sde.close();
    }
}
