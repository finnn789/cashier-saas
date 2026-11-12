package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.*;
import org.lite.project.cashiersaas.entity.shared.catalog.CategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.OptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductOptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.QCategoryEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.QOptionGroupEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.QProductEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.QProductOptionEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.QProductOptionGroupEntity;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbCatalogRepository {
    private static final QCategoryEntity CATEGORY = new QCategoryEntity("category");
    private static final QProductEntity PRODUCT = new QProductEntity("product");
    private static final QOptionGroupEntity GROUP = new QOptionGroupEntity("optionGroup");
    private static final QProductOptionEntity OPTION = new QProductOptionEntity("productOption");
    private static final QProductOptionGroupEntity LINK = new QProductOptionGroupEntity("productOptionGroup");
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<CategoryEntity> category(UUID tenant, UUID outlet, UUID id) {
        return criteriaBuilderFactory.create(entityManager, CategoryEntity.class, CATEGORY.getMetadata().getName())
                .where(CATEGORY.tenant.id.toString()).eq(tenant).where(CATEGORY.outlet.id.toString()).eq(outlet)
                .where(CATEGORY.id.toString()).eq(id).where(CATEGORY.archived.toString()).eq(false)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
    public List<CategoryEntity> categories(UUID tenant, UUID outlet) {
        return criteriaBuilderFactory.create(entityManager, CategoryEntity.class, CATEGORY.getMetadata().getName())
                .where(CATEGORY.tenant.id.toString()).eq(tenant).where(CATEGORY.outlet.id.toString()).eq(outlet)
                .where(CATEGORY.archived.toString()).eq(false).orderByAsc(CATEGORY.displayOrder.toString())
                .orderByAsc(CATEGORY.name.toString()).getResultList();
    }
    public Optional<ProductEntity> product(UUID tenant, UUID outlet, UUID id) {
        return criteriaBuilderFactory.create(entityManager, ProductEntity.class, PRODUCT.getMetadata().getName())
                .where(PRODUCT.tenant.id.toString()).eq(tenant).where(PRODUCT.outlet.id.toString()).eq(outlet)
                .where(PRODUCT.id.toString()).eq(id).where(PRODUCT.archived.toString()).eq(false)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
    public List<ProductEntity> products(UUID tenant, UUID outlet) {
        return criteriaBuilderFactory.create(entityManager, ProductEntity.class, PRODUCT.getMetadata().getName())
                .where(PRODUCT.tenant.id.toString()).eq(tenant).where(PRODUCT.outlet.id.toString()).eq(outlet)
                .where(PRODUCT.archived.toString()).eq(false).orderByAsc(PRODUCT.displayOrder.toString())
                .orderByAsc(PRODUCT.name.toString()).getResultList();
    }
    public Optional<OptionGroupEntity> group(UUID tenant, UUID outlet, UUID id) {
        return criteriaBuilderFactory.create(entityManager, OptionGroupEntity.class, GROUP.getMetadata().getName())
                .where(GROUP.tenant.id.toString()).eq(tenant).where(GROUP.outlet.id.toString()).eq(outlet)
                .where(GROUP.id.toString()).eq(id).where(GROUP.archived.toString()).eq(false)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
    public List<OptionGroupEntity> groups(UUID tenant, UUID outlet, UUID product) {
        return criteriaBuilderFactory.create(entityManager, OptionGroupEntity.class)
                .from(ProductOptionGroupEntity.class, LINK.getMetadata().getName())
                .innerJoin(LINK.optionGroup.toString(), GROUP.getMetadata().getName())
                .select(GROUP.getMetadata().getName())
                .where(LINK.tenant.id.toString()).eq(tenant).where(LINK.outlet.id.toString()).eq(outlet)
                .where(LINK.product.id.toString()).eq(product).where(GROUP.archived.toString()).eq(false)
                .orderByAsc(LINK.displayOrder.toString()).orderByAsc(GROUP.name.toString()).getResultList();
    }
    public List<OptionGroupEntity> outletGroups(UUID tenant, UUID outlet) {
        return criteriaBuilderFactory.create(entityManager, OptionGroupEntity.class, GROUP.getMetadata().getName())
                .where(GROUP.tenant.id.toString()).eq(tenant).where(GROUP.outlet.id.toString()).eq(outlet)
                .where(GROUP.archived.toString()).eq(false).orderByAsc(GROUP.displayOrder.toString())
                .orderByAsc(GROUP.name.toString()).getResultList();
    }
    public boolean groupInUse(UUID tenant, UUID outlet, UUID groupId) {
        return criteriaBuilderFactory.create(entityManager, Long.class)
                .from(ProductOptionGroupEntity.class, LINK.getMetadata().getName())
                .innerJoin(LINK.product.toString(), PRODUCT.getMetadata().getName())
                .select("COUNT(" + LINK.id + ")")
                .where(LINK.tenant.id.toString()).eq(tenant).where(LINK.outlet.id.toString()).eq(outlet)
                .where(LINK.optionGroup.id.toString()).eq(groupId).where(PRODUCT.archived.toString()).eq(false)
                .getSingleResult() > 0;
    }
    public void deleteProductGroups(UUID tenant, UUID outlet, UUID productId) {
        criteriaBuilderFactory.create(entityManager, ProductOptionGroupEntity.class, LINK.getMetadata().getName())
                .where(LINK.tenant.id.toString()).eq(tenant).where(LINK.outlet.id.toString()).eq(outlet)
                .where(LINK.product.id.toString()).eq(productId).getResultList().forEach(entityManager::remove);
        entityManager.flush();
    }
    public Optional<ProductOptionEntity> option(UUID tenant, UUID outlet, UUID id) {
        return criteriaBuilderFactory.create(entityManager, ProductOptionEntity.class, OPTION.getMetadata().getName())
                .where(OPTION.tenant.id.toString()).eq(tenant).where(OPTION.outlet.id.toString()).eq(outlet)
                .where(OPTION.id.toString()).eq(id).setMaxResults(1).getResultList().stream().findFirst();
    }
    public List<ProductOptionEntity> options(UUID tenant, UUID outlet, UUID group) {
        return criteriaBuilderFactory.create(entityManager, ProductOptionEntity.class, OPTION.getMetadata().getName())
                .where(OPTION.tenant.id.toString()).eq(tenant).where(OPTION.outlet.id.toString()).eq(outlet)
                .where(OPTION.optionGroup.id.toString()).eq(group).orderByAsc(OPTION.displayOrder.toString()).getResultList();
    }
    public void persist(CategoryEntity category) { entityManager.persist(category); }
    public void lock(CategoryEntity category) { entityManager.refresh(category, jakarta.persistence.LockModeType.PESSIMISTIC_READ); }
    public void lock(ProductEntity product) { entityManager.refresh(product, jakarta.persistence.LockModeType.PESSIMISTIC_READ); }
    public void lock(OptionGroupEntity group) { entityManager.refresh(group, jakarta.persistence.LockModeType.PESSIMISTIC_READ); }
    public void lock(ProductOptionEntity option) { entityManager.refresh(option, jakarta.persistence.LockModeType.PESSIMISTIC_READ); }
    public void persist(ProductEntity product) { entityManager.persist(product); }
    public void persist(OptionGroupEntity group) { entityManager.persist(group); }
    public void persist(ProductOptionEntity option) { entityManager.persist(option); }
    public void persist(ProductOptionGroupEntity link) { entityManager.persist(link); }
}
